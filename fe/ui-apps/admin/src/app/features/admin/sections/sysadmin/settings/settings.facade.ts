import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { Setting, SettingsApiService } from '../../../services/api/settings-api.service';

interface SettingsState {
  readonly settings: readonly Setting[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: SettingsState = { settings: [], loading: true, error: false };

/**
 * Page-scoped state for the Settings section: the editable-settings list plus
 * loading/error flags. The list endpoint carries NO `?lang=` (settings are
 * global, not localized), so the only fetch trigger is the mutation/refresh
 * tick — a language switch does not refetch.
 *
 * A failed load drives the `error` signal (the recipe-kit ErrorBanner),
 * deliberately surfacing the failure rather than silently showing an empty list.
 */
@Injectable()
export class SettingsFacade {
  private readonly api = inject(SettingsApiService);

  private readonly refreshTick = signal(0);

  private readonly state = toSignal(
    toObservable(this.refreshTick).pipe(
      switchMap(() =>
        this.api.getEditableSettings().pipe(
          map((settings): SettingsState => ({ settings, loading: false, error: false })),
          catchError(() => of<SettingsState>({ settings: [], loading: false, error: true })),
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly settings = computed(() => this.state().settings);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /** Refetch the list — init, after a successful save, on Cancel, and on import close. */
  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
