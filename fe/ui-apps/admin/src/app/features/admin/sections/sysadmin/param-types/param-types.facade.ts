import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError, map, startWith, switchMap } from 'rxjs/operators';

import {
  ParamType,
  ParamTypesAdminApiService,
} from '../../../services/api/param-types-admin-api.service';

interface ParamTypesState {
  readonly paramTypes: readonly ParamType[];
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: ParamTypesState = { paramTypes: [], loading: true, error: false };

/**
 * Page-scoped list state for Parameter Types — refetch tick → `getAll()`. The
 * payload is all-language (no `?lang=`); ordering is a client concern.
 */
@Injectable()
export class ParamTypesFacade {
  private readonly api = inject(ParamTypesAdminApiService);

  private readonly refreshTick = signal(0);

  private readonly state = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getAll().pipe(
          map((paramTypes): ParamTypesState => ({ paramTypes, loading: false, error: false })),
          catchError(() => of<ParamTypesState>({ paramTypes: [], loading: false, error: true })),
          startWith(LOADING),
        ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly paramTypes = computed(() => this.state().paramTypes);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
