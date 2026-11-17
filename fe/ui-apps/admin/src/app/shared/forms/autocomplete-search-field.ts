import { DestroyRef, Directive, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, debounceTime, map, of, switchMap } from 'rxjs';

/**
 * Shared scaffolding for the single-value autocomplete-search form controls
 * (message id, message series, user). It owns the debounced search pipeline and
 * the Signal Forms `disabled`/`touch` members; each subclass supplies
 * {@link searchApi} (and optionally {@link shouldSearch} / {@link searchDebounceMs})
 * plus its own typed `value` model and selection logic.
 *
 * Subclasses `extends AutocompleteSearchField<TSuggestion>` and
 * `implements FormValueControl<TValue>` (declaring their own `value = model<TValue>()`).
 */
@Directive()
export abstract class AutocompleteSearchField<TSuggestion> {
  protected readonly destroyRef = inject(DestroyRef);
  private readonly search$ = new Subject<string>();

  /** Suggestions from the most recent search (drives the autocomplete panel). */
  readonly suggestions = signal<TSuggestion[]>([]);
  /**
   * Whether the most recent search threw — bound to the autocomplete's empty
   * message so users get an explicit "Error fetching data" instead of the
   * misleading "No results found".
   */
  readonly fetchError = signal(false);
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  /** Debounce (ms) before a search fires; override per field. A method (not a field)
   * so an override is visible when the base constructor builds the pipe. */
  protected searchDebounceMs(): number {
    return 300;
  }

  /** Run the backend search for a query that passed {@link shouldSearch}. */
  protected abstract searchApi(query: string): Observable<readonly TSuggestion[]>;

  /** Whether a query is long enough to search (default: any non-empty query). */
  protected shouldSearch(query: string): boolean {
    return query.length > 0;
  }

  constructor() {
    this.search$
      .pipe(
        debounceTime(this.searchDebounceMs()),
        // Per-request catchError keeps the outer subscription alive after an API
        // failure; otherwise the autocomplete spinner would never resolve and
        // subsequent searches would be silently ignored.
        switchMap((query) =>
          this.searchApi(query).pipe(
            map((items) => ({ items, error: false as const })),
            catchError(() => of({ items: [] as readonly TSuggestion[], error: true as const })),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ items, error }) => {
        this.suggestions.set([...items]);
        this.fetchError.set(error);
      });
  }

  /** Feed the debounced search; a too-short/blank query clears suggestions immediately. */
  onSearch(query: string): void {
    if (!this.shouldSearch(query)) {
      this.suggestions.set([]);
      this.fetchError.set(false);
      return;
    }
    this.search$.next(query);
  }

  /** Bridge the primitive's `(touched)` (blur) to Signal Forms touched-tracking. */
  markTouched(): void {
    this.touch.emit();
  }
}
