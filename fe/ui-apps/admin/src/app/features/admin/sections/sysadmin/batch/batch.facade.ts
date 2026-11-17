import { DOCUMENT, DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of, startWith, switchMap, tap } from 'rxjs';

import {
  BatchApiService,
  BatchExecution,
  BatchInstance,
  BatchType,
} from '../../../services/api/batch-api.service';
import { clampPageToTotal } from '../../../utils/server-page-clamp';

/** Foreground page-poll cadence — distinct from the 10s rail-badge poller. */
const POLL_INTERVAL_MS = 3000;

export const BATCH_PAGE_SIZE = 5;

/**
 * A flattened executions-table row. An `instance-header` row carries the
 * instance for the once-per-instance header cell (job no, user, download/logs);
 * an `execution` row carries one execution (start/end/progress, status, actions).
 * The flatten never mutates the VOs — the discriminator is an explicit row type.
 */
export type BatchRow =
  | { readonly kind: 'instance-header'; readonly instance: BatchInstance }
  | {
      readonly kind: 'execution';
      readonly instance: BatchInstance;
      readonly execution: BatchExecution;
    };

interface InstancesState {
  readonly rows: readonly BatchRow[];
  readonly total: number;
  readonly loading: boolean;
}

const EMPTY_INSTANCES: InstancesState = { rows: [], total: 0, loading: false };
const LOADING_INSTANCES: InstancesState = { rows: [], total: 0, loading: true };

/**
 * Flatten a page of instances into the discriminated row list: one header row
 * per instance followed by one row per execution. Pure — never mutates input.
 */
function flatten(instances: readonly BatchInstance[]): BatchRow[] {
  const rows: BatchRow[] = [];
  for (const instance of instances) {
    rows.push({ kind: 'instance-header', instance });
    for (const execution of instance.executions) {
      rows.push({ kind: 'execution', instance, execution });
    }
  }
  return rows;
}

/**
 * Page-scoped state for the Batch Jobs monitor. Two independent concerns:
 *
 * 1. A 3s FOREGROUND poll of `/batch/status` (the type list + counts), paused
 *    while `document.hidden` and resumed with an immediate load on return. This
 *    poller is SEPARATE from the shell's 10s badge `BatchStatusService` — they
 *    coexist without coordination. Poll failures keep the last status (silent).
 * 2. A server-paged instances fetch for the selected type, keyed on type + page
 *    + the poll tick (every poll refetches the current page).
 *    `clampPageToTotal` fires one corrective refetch when a type's instance
 *    count shrinks. Fetch failures keep the last rows (silent keep-last).
 *
 * `selectType` resets the page to 0 and clears the rows immediately on a switch;
 * the optional `:batchName` pre-selection is applied ONCE by the page on entry.
 */
@Injectable()
export class BatchFacade {
  private readonly api = inject(BatchApiService);
  private readonly document = inject(DOCUMENT);

  /** Status (type list + counts) — kept last on a failed poll. */
  readonly types = signal<readonly BatchType[]>([]);

  readonly selectedType = signal<string | undefined>(undefined);
  private readonly page = signal(0);

  /**
   * 0 marks a FRESH selection/page (show the loading shell on the first fetch);
   * each 3s poll bumps it so subsequent refetches refresh in place without a
   * flash.
   */
  private readonly pollTick = signal(0);

  readonly first = computed(() => this.page() * BATCH_PAGE_SIZE);
  readonly pageSize = BATCH_PAGE_SIZE;

  private readonly trigger = computed(() => ({
    type: this.selectedType(),
    page: this.page(),
    tick: this.pollTick(),
  }));

  /**
   * The rendered instances state. A writable signal (not a raw toSignal output)
   * so `selectType` can clear it SYNCHRONOUSLY (the table clears immediately on
   * a type switch) and so the keep-last catch reads the live
   * value without a self-referential glitch. The constructor subscription below
   * pushes each fetch result here.
   */
  private readonly instances = signal<InstancesState>(EMPTY_INSTANCES);

  readonly rows = computed(() => this.instances().rows);
  readonly total = computed(() => this.instances().total);
  readonly instancesLoading = computed(() => this.instances().loading);

  private timer: ReturnType<typeof setInterval> | undefined;
  private readonly onVisibilityChange = (): void => {
    if (this.document.hidden) {
      this.stopTimer();
    } else {
      this.beginPolling();
    }
  };

  constructor() {
    // The instances fetch: keyed on selected-type + page + poll tick. A fresh
    // selection/page (tick 0) shows the loading shell; poll-driven refetches
    // refresh in place. Failures keep the last rows (silent keep-last). The
    // result is pushed into the writable `instances` signal the view reads.
    toObservable(this.trigger)
      .pipe(
        switchMap(({ type, page, tick }) => {
          if (!type) {
            // No selected type → no instances (rail-click no-param landing).
            return of(EMPTY_INSTANCES);
          }
          const fetch$: Observable<InstancesState> = this.api
            .getInstances(type, page, BATCH_PAGE_SIZE)
            .pipe(
              tap((result) => {
                const clamped = clampPageToTotal(page, BATCH_PAGE_SIZE, result.total);
                if (clamped !== null) {
                  this.page.set(clamped);
                }
              }),
              map(
                (result): InstancesState => ({
                  rows: flatten(result.data),
                  total: result.total,
                  loading: false,
                }),
              ),
              catchError(() => of({ ...this.instances(), loading: false })),
            );
          return tick === 0 ? fetch$.pipe(startWith(LOADING_INSTANCES)) : fetch$;
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.instances.set(state));

    inject(DestroyRef).onDestroy(() => {
      this.stopTimer();
      this.document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
    this.document.addEventListener('visibilitychange', this.onVisibilityChange);
    if (!this.document.hidden) {
      this.beginPolling();
    } else {
      // Hidden at startup: still do the initial status load so the type list is
      // ready; the interval starts when the tab returns to the foreground.
      this.loadStatus();
    }
  }

  /**
   * Select a job type: reset to page 0 and clear the rows immediately (the
   * table clears synchronously). The one-time `:batchName` pre-selection calls
   * this too — indistinguishable from a click for the fetch.
   */
  selectType(type: string | undefined): void {
    if (type === this.selectedType()) {
      return;
    }
    this.selectedType.set(type);
    this.page.set(0);
    this.pollTick.set(0); // a fresh selection shows the loading shell
    // Clear the table immediately — the new fetch hasn't returned yet.
    this.instances.set(LOADING_INSTANCES);
  }

  setFirst(first: number): void {
    this.page.set(Math.floor(first / BATCH_PAGE_SIZE));
  }

  /** Re-run the current page immediately (after a successful stop/restart/abandon). */
  refreshInstances(): void {
    this.pollTick.update((tick) => tick + 1);
  }

  private beginPolling(): void {
    if (this.timer !== undefined) {
      return;
    }
    this.loadStatus();
    this.timer = setInterval(() => this.loadStatus(), POLL_INTERVAL_MS);
  }

  private stopTimer(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  /**
   * Poll the status payload (type list + counts). On success, also bump the
   * poll tick so the instances table refetches the current page (every status
   * poll re-fetches the instances). Failures keep the last status.
   */
  private loadStatus(): void {
    this.api.getStatus().subscribe({
      next: (status) => {
        this.types.set(status.types);
        this.pollTick.update((tick) => tick + 1);
      },
      error: () => undefined,
    });
  }
}
