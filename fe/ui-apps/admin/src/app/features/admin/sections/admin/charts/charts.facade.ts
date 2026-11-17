import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { Chart, ChartsAdminApiService } from '../../../services/api/charts-admin-api.service';

interface ChartsState {
  readonly charts: readonly Chart[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: ChartsState = { charts: [], loading: true, error: false };

/**
 * Page-scoped list state for the Charts section. Charts are language-agnostic
 * (the list endpoint carries no `?lang=`), so the fetch trigger is the refresh
 * tick only — a language switch does NOT refetch.
 */
@Injectable()
export class ChartsFacade {
  private readonly api = inject(ChartsAdminApiService);

  private readonly refreshTick = signal(0);

  private readonly state = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getAll().pipe(
          map((charts): ChartsState => ({ charts, loading: false, error: false })),
          catchError(() => of<ChartsState>({ charts: [], loading: false, error: true })),
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly charts = computed(() => this.state().charts);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
