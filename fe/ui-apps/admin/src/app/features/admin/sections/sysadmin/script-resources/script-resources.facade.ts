import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError, map, startWith, switchMap } from 'rxjs/operators';

import {
  ScriptResource,
  ScriptResourcesApiService,
} from '../../../services/api/script-resources-api.service';

interface ResourcesState {
  readonly resources: readonly ScriptResource[];
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: ResourcesState = { resources: [], loading: true, error: false };

/**
 * Page-scoped list state for Script Resources — a refetch tick drives a single
 * `getAll()`, surfacing loading/error for the list-state shells. Provided in the
 * page's `providers`, not root.
 */
@Injectable()
export class ScriptResourcesFacade {
  private readonly api = inject(ScriptResourcesApiService);

  private readonly refreshTick = signal(0);

  /** The whole list state; `loading` is true until each `getAll()` settles. */
  readonly state = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getAll().pipe(
          map((resources): ResourcesState => ({ resources, loading: false, error: false })),
          catchError(() => of<ResourcesState>({ resources: [], loading: false, error: true })),
          startWith(LOADING),
        ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly resources = computed(() => this.state().resources);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
