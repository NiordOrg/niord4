import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import { LocaleDatePipe } from '../../../../../shared/pipes/locale-date.pipe';
import { S124Dataset, SecomAdminApiService } from '../../../services/api/secom-admin-api.service';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SecomGmlDialog } from './secom-gml-dialog';
import { datasetStatusClass, datasetStatusKey } from './secom-labels';
import { SecomFacade, SecomSeriesExposure } from './secom.facade';

/**
 * Datasets tab: which NW message series are exposed as S-124, which languages
 * their datasets may carry, and the dataset instances generated from them.
 *
 * A series is closed by default; ticking it makes published messages of that
 * series both pushed to matching subscribers and served over SECOM Get. The
 * language checkboxes narrow the model languages the datasets carry; with none
 * ticked a dataset carries every model language its message has content in.
 * **Reconcile** diffs eligible messages against the dataset store — the routine
 * repair, and the backfill when SECOM is first switched on.
 */
@Component({
  selector: 'app-secom-datasets-tab',
  imports: [
    EmptyState,
    ErrorBanner,
    Icon,
    LocaleDatePipe,
    Pager,
    ProgressBar,
    RouterLink,
    SecomGmlDialog,
    TranslocoPipe,
  ],
  templateUrl: './secom-datasets-tab.html',
  host: { class: 'contents' },
})
export class SecomDatasetsTab {
  private readonly languageSettings = inject(LanguageService);
  protected readonly facade = inject(SecomFacade);
  private readonly api = inject(SecomAdminApiService);
  private readonly notifications = inject(NotificationService);

  private readonly gmlDialog = viewChild.required(SecomGmlDialog);

  /**
   * Series rows changed and PERSISTED since the list was loaded, layered over
   * the facade rows so the ticks stay put without re-entering the list's loading
   * state on every toggle. Only ever holds rows the backend accepted (as it
   * echoed them); a rejected save drops its entry and reloads.
   */
  private readonly persistedOverrides = signal<ReadonlyMap<string, SecomSeriesExposure>>(new Map());

  /** Series ids with a save in flight — the row's checkboxes are disabled meanwhile. */
  private readonly savingSeries = signal<ReadonlySet<string>>(new Set());

  /**
   * The deployment's model languages, one checkbox per row each. The backend
   * validates against ITS model-language setting; the two lists describe the same
   * deployment, so a mismatch is a configuration error it reports as a 400.
   */
  protected readonly languages = this.languageSettings.modelLanguages;

  /**
   * Dataset uuids with a republish in flight. Republish is NOT idempotent — the
   * backend enqueues one upload per matching subscription on every call — so a
   * second click before the first POST returns would send the peers a duplicate
   * of the same dataset. Keyed per dataset so republishing one row never blocks
   * another.
   */
  private readonly republishing = signal<ReadonlySet<string>>(new Set());

  protected readonly seriesRows = computed<readonly SecomSeriesExposure[]>(() => {
    const overrides = this.persistedOverrides();
    return this.facade.seriesExposure().map((row) => overrides.get(row.seriesId) ?? row);
  });

  protected readonly datasetPager = createClientPager(this.facade.datasets, ADMIN_PAGE_SIZE);

  /** Materialize the readonly page slice for the template. */
  protected readonly datasetRows = computed(() => [...this.datasetPager.paged()]);

  /** Blocks re-entry while a reconciliation sweep is running. */
  protected readonly reconciling = signal(false);

  protected isSavingSeries(seriesId: string): boolean {
    return this.savingSeries().has(seriesId);
  }

  protected isRepublishing(uuid: string): boolean {
    return this.republishing().has(uuid);
  }

  protected toggleSeries(row: SecomSeriesExposure, exposeOverSecom: boolean): void {
    this.saveSeries({ ...row, exposeOverSecom });
  }

  protected hasLanguage(row: SecomSeriesExposure, lang: string): boolean {
    return row.languages.includes(lang);
  }

  /**
   * Ticks or unticks one language of the row's restriction. The list goes out in
   * the deployment's language order; the backend stores it in its own canonical
   * order and echoes that back, which is what the row then shows. Unticking the
   * last language sends an empty list: no restriction.
   */
  protected toggleLanguage(row: SecomSeriesExposure, lang: string, selected: boolean): void {
    const current: ReadonlySet<string> = new Set(row.languages);
    const next = selected ? withKey(current, lang) : withoutKey(current, lang);
    const languages = this.languages.map((l) => l.value).filter((value) => next.has(value));
    this.saveSeries({ ...row, languages });
  }

  /**
   * PUTs the whole row (the backend replaces it as sent). One save per series at
   * a time: a click while the previous PUT is in flight is dropped rather than
   * queued, so two quick toggles cannot race each other's stale row.
   */
  private saveSeries(next: SecomSeriesExposure): void {
    const { seriesId } = next;
    if (this.savingSeries().has(seriesId)) {
      return;
    }
    this.savingSeries.update((saving) => withKey(saving, seriesId));
    this.api.updateSeriesConfig(seriesId, next.exposeOverSecom, next.languages).subscribe({
      next: (config) => {
        this.clearSaving(seriesId);
        this.persistedOverrides.update((overrides) => new Map(overrides).set(seriesId, config));
        this.notifications.success('admin.secom.toast.seriesUpdated', { seriesId });
        // The exposed-series line and the dataset counts on Overview move with it.
        this.facade.refreshStatus();
      },
      error: () => {
        this.clearSaving(seriesId);
        this.persistedOverrides.update((overrides) => {
          const next = new Map(overrides);
          next.delete(seriesId);
          return next;
        });
        this.notifications.error('admin.secom.toast.seriesUpdateError', { seriesId });
        this.facade.refreshSeriesExposure();
      },
    });
  }

  protected reconcile(): void {
    if (this.reconciling()) {
      return;
    }
    this.reconciling.set(true);
    this.api.reconcile().subscribe({
      next: (result) => {
        this.reconciling.set(false);
        this.notifications.info('admin.secom.toast.reconciled', {
          created: result.created,
          updated: result.updated,
          cancelled: result.cancelled,
          unchanged: result.unchanged,
          failed: result.failed,
        });
        this.facade.refreshDatasets();
        this.facade.refreshOutbox();
        this.facade.refreshStatus();
      },
      error: () => {
        this.reconciling.set(false);
        this.notifications.error('admin.secom.toast.reconcileError');
      },
    });
  }

  protected viewGml(dataset: S124Dataset): void {
    this.gmlDialog().open(dataset);
  }

  /**
   * Re-enqueues the dataset to every matching subscription. Guarded per dataset:
   * a repeat click while the POST is in flight is dropped, because a second call
   * would enqueue a second delivery to every one of those subscriptions.
   */
  protected republish(dataset: S124Dataset): void {
    const { uuid } = dataset;
    if (this.republishing().has(uuid)) {
      return;
    }
    this.republishing.update((pending) => withKey(pending, uuid));
    this.api.republishDataset(uuid).subscribe({
      next: (count) => {
        this.republishing.update((pending) => withoutKey(pending, uuid));
        this.notifications.info('admin.secom.toast.republished', { count });
        this.facade.refreshOutbox();
        this.facade.refreshStatus();
      },
      error: () => {
        this.republishing.update((pending) => withoutKey(pending, uuid));
        this.notifications.error('admin.secom.toast.republishError');
      },
    });
  }

  /** Layer-3 status-pill class + translated label key — never the raw enum. */
  protected readonly datasetStatusClass = datasetStatusClass;
  protected readonly datasetStatusKey = datasetStatusKey;

  private clearSaving(seriesId: string): void {
    this.savingSeries.update((saving) => withoutKey(saving, seriesId));
  }
}

/** Immutable set add/remove — the in-flight signals must never be mutated in place. */
function withKey(set: ReadonlySet<string>, key: string): ReadonlySet<string> {
  return new Set(set).add(key);
}

function withoutKey(set: ReadonlySet<string>, key: string): ReadonlySet<string> {
  const next = new Set(set);
  next.delete(key);
  return next;
}
