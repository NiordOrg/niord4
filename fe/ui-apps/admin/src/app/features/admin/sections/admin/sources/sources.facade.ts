import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { Source, SourcesApiService } from '../../../../messages/services/api/sources-api.service';

interface SourcesState {
  readonly sources: readonly Source[];
  readonly loading: boolean;
  readonly error: boolean;
}

const INITIAL: SourcesState = { sources: [], loading: true, error: false };

/**
 * Page-scoped list state for the Sources section. The fetch trigger includes
 * the selected model language (the list endpoint is language-filtered server-side),
 * so a language switch refetches; mutations bump `refresh()` to refetch after
 * create/update/delete/import.
 */
@Injectable()
export class SourcesFacade {
  private readonly api = inject(SourcesApiService);
  private readonly language = inject(LanguageService);
  private readonly modelLanguage = inject(ModelLanguageSelection, { optional: true });

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    lang: this.modelLanguage?.value() ?? this.language.selectedLanguage(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ lang }) =>
        this.api.getAll(lang).pipe(
          map((sources): SourcesState => ({ sources, loading: false, error: false })),
          catchError(() => of<SourcesState>({ sources: [], loading: false, error: true })),
          // Every (re)fetch — initial, language switch, refresh — shows the
          // loading shell until the response lands.
          startWith(INITIAL),
        ),
      ),
    ),
    { initialValue: INITIAL },
  );

  readonly sources = computed(() => this.state().sources);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
