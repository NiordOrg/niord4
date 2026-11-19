import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { fixedListRead } from '../../../../../shared/utils/list-read';
import {
  DictionariesApiService,
  DictionaryEntry,
} from '../../../services/api/dictionaries-api.service';

interface EntriesState {
  readonly entries: readonly DictionaryEntry[];
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: EntriesState = { entries: [], loading: true, error: false };

/**
 * Page-scoped state for the Dictionaries section. The entries payload carries
 * ALL languages (no lang param anywhere — ordering is a client concern), so a
 * UI language switch does NOT refetch; the trigger is dictionary name + the
 * mutation tick.
 */
@Injectable()
export class DictionariesFacade {
  private readonly api = inject(DictionariesApiService);

  private readonly requestedName = signal('web');

  // A failed read of the names leaves the selector empty: it counts as a failed load
  // (`error`, `namesFailed`), so the page says so rather than offering no dictionaries.
  private readonly namesRead = fixedListRead<string>(() => this.api.getDictionaryNames());
  readonly names = this.namesRead.value;
  readonly namesFailed = this.namesRead.failed;

  /**
   * Selected dictionary, validated against the loaded names: when the default
   * 'web' (or any requested name) is absent from the deployment's
   * list, fall back to the first available name instead of fetching a
   * non-existent dictionary.
   */
  readonly currentName = computed(() => {
    const names = this.names();
    const requested = this.requestedName();
    return names.length === 0 || names.includes(requested) ? requested : names[0];
  });

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    name: this.currentName(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ name }) =>
        this.api.getDictionaryEntries(name).pipe(
          map((entries): EntriesState => ({ entries, loading: false, error: false })),
          catchError(() => of<EntriesState>({ entries: [], loading: false, error: true })),
          startWith(LOADING),
        ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly entries = computed(() => this.state().entries);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error || this.namesFailed());

  /** Switch dictionary only when the name is known — else keep current. */
  selectDictionary(name: string): void {
    if (this.names().includes(name)) {
      this.requestedName.set(name);
    }
  }

  /** Read the current dictionary again, and the names too when they failed. */
  refresh(): void {
    if (this.namesFailed()) {
      this.namesRead.retry();
    }
    this.refreshTick.update((tick) => tick + 1);
  }
}
