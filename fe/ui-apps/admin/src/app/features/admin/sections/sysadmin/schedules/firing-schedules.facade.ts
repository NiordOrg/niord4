import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import {
  FiringSchedule,
  FiringSchedulesApiService,
} from '../../../services/api/firing-schedules-api.service';

interface FiringSchedulesState {
  readonly schedules: readonly FiringSchedule[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: FiringSchedulesState = { schedules: [], loading: true, error: false };

/**
 * Page-scoped list state for the Firing Schedules section. The fetch trigger
 * includes the selected UI language (the list endpoint localizes domain/series
 * names server-side), so a language switch refetches; mutations bump `refresh()`
 * to refetch after create/update/delete.
 */
@Injectable()
export class FiringSchedulesFacade {
  private readonly api = inject(FiringSchedulesApiService);
  private readonly language = inject(LanguageService);

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    lang: this.language.selectedLanguage(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ lang }) =>
        this.api.getAll(lang).pipe(
          map((schedules): FiringSchedulesState => ({ schedules, loading: false, error: false })),
          catchError(() =>
            of<FiringSchedulesState>({ schedules: [], loading: false, error: true }),
          ),
          // Every (re)fetch — initial, language switch, refresh — shows the
          // loading shell until the response lands.
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly schedules = computed(() => this.state().schedules);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
