import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, forkJoin, map, of, startWith, switchMap } from 'rxjs';

import { MessageSeriesApiService } from '../../../../messages/services/api/message-series-api.service';
import {
  S124Dataset,
  SecomAdminApiService,
  SecomNode,
  SecomOutboxEntry,
  SecomOutboxStatus,
  SecomSeriesConfig,
  SecomStatus,
  SecomSubscription,
} from '../../../services/api/secom-admin-api.service';

/**
 * One section's load outcome. `notFound` is kept apart from `error` because a
 * 404 from `/secom/**` means the subsystem is not deployed on this node, which
 * is a legitimate configuration rather than a failure.
 */
interface LoadState<T> {
  readonly data: T;
  readonly loading: boolean;
  readonly error: boolean;
  readonly notFound: boolean;
}

function initialState<T>(empty: T): LoadState<T> {
  return { data: empty, loading: true, error: false, notFound: false };
}

/**
 * Wires a trigger signal to a fetch: every trigger emission restarts the
 * request (`switchMap` cancels a superseded one), re-entering the loading state
 * so a refresh shows progress. Must be called from an injection context.
 */
function createLoader<T>(
  trigger: Signal<unknown>,
  fetch: () => Observable<T>,
  empty: T,
): Signal<LoadState<T>> {
  const initial = initialState(empty);
  return toSignal(
    toObservable(trigger).pipe(
      switchMap(() =>
        fetch().pipe(
          map((data): LoadState<T> => ({ data, loading: false, error: false, notFound: false })),
          catchError((err: unknown) =>
            of<LoadState<T>>({
              data: empty,
              loading: false,
              error: true,
              notFound: err instanceof HttpErrorResponse && err.status === 404,
            }),
          ),
          startWith(initial),
        ),
      ),
    ),
    { initialValue: initial },
  );
}

/**
 * One row of the S-124 exposure table: an NW series plus its stored exposure flag
 * and language restriction (empty = every model language the message carries).
 */
export interface SecomSeriesExposure {
  readonly seriesId: string;
  readonly exposeOverSecom: boolean;
  readonly languages: readonly string[];
}

const NO_SERIES: readonly SecomSeriesExposure[] = [];
const NO_SUBSCRIPTIONS: readonly SecomSubscription[] = [];
const NO_NODES: readonly SecomNode[] = [];
const NO_DATASETS: readonly S124Dataset[] = [];
const NO_OUTBOX: readonly SecomOutboxEntry[] = [];

/**
 * Page-scoped state for the SECOM section. The five tab panels render eagerly,
 * so all six loads fire together on mount and each tab reads its own slice; a
 * mutation refreshes only the slices it touched.
 *
 * Nothing here is localized — no request carries `?lang=`, and a language switch
 * deliberately does not refetch.
 *
 * **Subsystem detection.** When the backend runs without `niord.secom.enabled`
 * the whole `/secom` controller is absent and every endpoint 404s. `unavailable`
 * reads that off the STATUS probe alone, deliberately: a genuine 500 from any of
 * the six loads still surfaces as a per-section error instead of being
 * mislabelled "SECOM is disabled".
 */
@Injectable()
export class SecomFacade {
  private readonly api = inject(SecomAdminApiService);
  private readonly seriesApi = inject(MessageSeriesApiService);

  private readonly statusTick = signal(0);
  private readonly seriesTick = signal(0);
  private readonly subscriptionsTick = signal(0);
  private readonly nodesTick = signal(0);
  private readonly datasetsTick = signal(0);
  private readonly outboxTick = signal(0);

  /** Outbox status filter; `''` = all (the request then omits the param). */
  readonly outboxStatus = signal<'' | SecomOutboxStatus>('');

  private readonly statusState = createLoader(
    this.statusTick,
    () => this.api.getStatus(),
    undefined as SecomStatus | undefined,
  );

  /**
   * The exposure table is the NW message series joined with the stored config
   * rows — series without a row are simply not exposed. Modelled on the series
   * list (not the config list) so a series whose row was deleted still appears,
   * unchecked.
   */
  private readonly seriesState = createLoader(
    this.seriesTick,
    () =>
      forkJoin({
        series: this.seriesApi.getAll(),
        configs: this.api.getSeriesConfigs(),
      }).pipe(map(({ series, configs }) => mergeSeriesExposure(series, configs))),
    NO_SERIES,
  );

  private readonly subscriptionsState = createLoader(
    this.subscriptionsTick,
    () => this.api.getSubscriptions(),
    NO_SUBSCRIPTIONS,
  );

  private readonly nodesState = createLoader(this.nodesTick, () => this.api.getNodes(), NO_NODES);

  private readonly datasetsState = createLoader(
    this.datasetsTick,
    () => this.api.getDatasets(),
    NO_DATASETS,
  );

  /** Refetches on a tick OR a status-filter change — both are part of the request key. */
  private readonly outboxKey = computed(() => ({
    tick: this.outboxTick(),
    status: this.outboxStatus(),
  }));

  private readonly outboxState = createLoader(
    this.outboxKey,
    () => this.api.getOutbox(this.outboxStatus()),
    NO_OUTBOX,
  );

  readonly status = computed(() => this.statusState().data);
  readonly statusLoading = computed(() => this.statusState().loading);
  readonly statusError = computed(() => this.statusState().error && !this.unavailable());

  /** True when this node runs without the SECOM subsystem — the page then explains itself. */
  readonly unavailable = computed(() => this.statusState().notFound);

  readonly seriesExposure = computed(() => this.seriesState().data);
  readonly seriesLoading = computed(() => this.seriesState().loading);
  readonly seriesError = computed(() => this.seriesState().error);

  readonly subscriptions = computed(() => this.subscriptionsState().data);
  readonly subscriptionsLoading = computed(() => this.subscriptionsState().loading);
  readonly subscriptionsError = computed(() => this.subscriptionsState().error);

  readonly nodes = computed(() => this.nodesState().data);
  readonly nodesLoading = computed(() => this.nodesState().loading);
  readonly nodesError = computed(() => this.nodesState().error);

  readonly datasets = computed(() => this.datasetsState().data);
  readonly datasetsLoading = computed(() => this.datasetsState().loading);
  readonly datasetsError = computed(() => this.datasetsState().error);

  readonly outbox = computed(() => this.outboxState().data);
  readonly outboxLoading = computed(() => this.outboxState().loading);
  readonly outboxError = computed(() => this.outboxState().error);

  refreshStatus(): void {
    this.statusTick.update((tick) => tick + 1);
  }

  refreshSeriesExposure(): void {
    this.seriesTick.update((tick) => tick + 1);
  }

  refreshSubscriptions(): void {
    this.subscriptionsTick.update((tick) => tick + 1);
  }

  refreshNodes(): void {
    this.nodesTick.update((tick) => tick + 1);
  }

  refreshDatasets(): void {
    this.datasetsTick.update((tick) => tick + 1);
  }

  refreshOutbox(): void {
    this.outboxTick.update((tick) => tick + 1);
  }

  setOutboxStatus(status: '' | SecomOutboxStatus): void {
    this.outboxStatus.set(status);
  }
}

/**
 * Joins the NW message series with the stored exposure rows. Only NW series can
 * be exposed as S-124, so NM series are filtered out; a series with no stored
 * row reads as not exposed.
 */
function mergeSeriesExposure(
  series: readonly { seriesId: string; mainType?: string }[],
  configs: readonly SecomSeriesConfig[],
): readonly SecomSeriesExposure[] {
  const stored = new Map(configs.map((config) => [config.seriesId, config]));
  return series
    .filter((entry) => entry.mainType === 'NW')
    .map((entry) => {
      const config = stored.get(entry.seriesId);
      return {
        seriesId: entry.seriesId,
        exposeOverSecom: config?.exposeOverSecom === true,
        languages: config?.languages ?? [],
      };
    });
}
