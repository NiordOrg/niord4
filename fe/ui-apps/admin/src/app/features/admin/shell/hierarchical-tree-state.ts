import { computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { type Observable, catchError, map, of, startWith, switchMap } from 'rxjs';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { ModelLanguageSelection } from '../services/model-language-selection';
import type { HierarchicalEntity } from '../../../shared/components/entity-tree/hierarchical-entity.model';

interface TreeState<T> {
  readonly entities: readonly T[];
  readonly loading: boolean;
  readonly error: boolean;
}

/** The reactive tree state a hierarchical-admin facade exposes. */
export interface HierarchicalTreeState<T> {
  readonly entities: () => readonly T[];
  readonly loading: () => boolean;
  readonly error: () => boolean;
  readonly refresh: () => void;
}

/**
 * Shared reactive tree-loading state for the hierarchical admin facades (Areas, Categories): loads the
 * roots (with nested children) via `getRoots`, refetches on a language switch (names are
 * language-filtered) and on `refresh()` after every mutation. Provided as a helper (not a base class)
 * so the `getRoots` closure captures the subclass's already-initialised API field — a base-class field
 * initialiser would subscribe before the subclass's fields exist.
 *
 * Call from a facade's field initialiser (runs in the injection context):
 * `private readonly tree = hierarchicalTreeState((lang) => this.api.getRoots({ language: lang }));`
 */
export function hierarchicalTreeState<T extends HierarchicalEntity>(
  getRoots: (language: string) => Observable<readonly T[]>,
): HierarchicalTreeState<T> {
  const language = inject(LanguageService);
  const modelLanguage = inject(ModelLanguageSelection, { optional: true });
  const refreshTick = signal(0);
  const initial: TreeState<T> = { entities: [], loading: true, error: false };

  const trigger = computed(() => ({
    lang: modelLanguage?.value() ?? language.selectedLanguage(),
    tick: refreshTick(),
  }));

  const state = toSignal(
    toObservable(trigger).pipe(
      switchMap(({ lang }) =>
        getRoots(lang).pipe(
          map((entities): TreeState<T> => ({ entities, loading: false, error: false })),
          catchError(() => of<TreeState<T>>({ entities: [], loading: false, error: true })),
          startWith(initial),
        ),
      ),
    ),
    { initialValue: initial },
  );

  return {
    entities: computed(() => state().entities),
    loading: computed(() => state().loading),
    error: computed(() => state().error),
    refresh: () => refreshTick.update((tick) => tick + 1),
  };
}
