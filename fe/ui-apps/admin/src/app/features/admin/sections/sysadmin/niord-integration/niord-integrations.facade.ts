import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { MessageSeriesApiService } from '../../../../messages/services/api/message-series-api.service';
import {
  NiordIntegration,
  NiordIntegrationsApiService,
} from '../../../services/api/niord-integrations-api.service';

interface IntegrationsState {
  readonly integrations: readonly NiordIntegration[];
  readonly loading: boolean;
  readonly error: boolean;
}

interface SeriesOptionsState {
  readonly options: readonly string[];
  readonly error: boolean;
}

const INITIAL_LIST: IntegrationsState = { integrations: [], loading: true, error: false };
const INITIAL_SERIES: SeriesOptionsState = { options: [], error: false };

/**
 * Page-scoped state for the Niord Integration section: the integrations list
 * plus the full cross-domain target-series option list.
 *
 * The list endpoint carries NO `?lang=` (nothing localized), so the fetch
 * trigger is the mutation tick ONLY — a language switch does not refetch. The
 * series options are fetched once on init; a fetch failure is SURFACED via the
 * `seriesError()` signal rather than silently leaving the dropdown empty.
 */
@Injectable()
export class NiordIntegrationsFacade {
  private readonly api = inject(NiordIntegrationsApiService);
  private readonly seriesApi = inject(MessageSeriesApiService);

  private readonly refreshTick = signal(0);

  private readonly listState = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getAll().pipe(
          map(
            (integrations): IntegrationsState => ({ integrations, loading: false, error: false }),
          ),
          catchError(() =>
            of<IntegrationsState>({ integrations: [], loading: false, error: true }),
          ),
          startWith(INITIAL_LIST),
        ),
      ),
    ),
    { initialValue: INITIAL_LIST },
  );

  private readonly seriesState = toSignal(
    this.seriesApi.getAll().pipe(
      map(
        (series): SeriesOptionsState => ({ options: series.map((s) => s.seriesId), error: false }),
      ),
      catchError(() => of<SeriesOptionsState>({ options: [], error: true })),
    ),
    { initialValue: INITIAL_SERIES },
  );

  readonly integrations = computed(() => this.listState().integrations);
  readonly loading = computed(() => this.listState().loading);
  readonly error = computed(() => this.listState().error);

  readonly seriesOptions = computed(() => this.seriesState().options);
  readonly seriesError = computed(() => this.seriesState().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
