import {
  computed,
  DestroyRef,
  Directive,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormValueControl } from '@angular/forms/signals';
import { LanguageService } from '../../core/main-shell/services/language.service';
import {
  Observable,
  Subject,
  catchError,
  debounceTime,
  defer,
  distinctUntilChanged,
  finalize,
  map,
  of,
  switchMap,
} from 'rxjs';

/**
 * Config for the magnifier (browse) button that `EntityMultiselectControl`
 * renders after the autocomplete when a field overrides {@link EntityMultiselectField#magnifier}.
 */
export interface EntityMultiselectMagnifier {
  /** Lucide icon name for the button. */
  readonly icon: string;
  /** i18n key for the button's aria-label + title (the control translates). */
  readonly tooltipKey: string;
  /** Extra classes appended to the button's `icon-button` class (e.g. `'text-xs'`). */
  readonly buttonClass?: string;
  /** Opens the leaf's picker surface; `event.currentTarget` is the button (popover anchor). */
  onClick(event: MouseEvent): void;
}

@Directive()
export abstract class EntityMultiselectField<
  TItem,
  TId extends string | number,
> implements FormValueControl<readonly TItem[]> {
  protected readonly destroyRef = inject(DestroyRef);
  private readonly language = inject(LanguageService);
  private readonly search$ = new Subject<string>();
  private readonly resolveIds$ = new Subject<{ ids: readonly TId[]; lang: string }>();
  private readonly resolving = signal(0);

  readonly initialIds = input<readonly TId[] | null>(null);
  readonly placeholder = input('');
  /**
   * i18n key for the control's accessible name. Defaults to the `placeholder`
   * key so the `<app-autocomplete>` always has a name for screen readers even
   * when a consumer only sets a placeholder. The leaf template resolves it with
   * `| transloco` (translation-ownership rule). Consumers may override with a
   * dedicated key when the placeholder text isn't a good label.
   */
  readonly ariaLabel = input('');
  readonly ariaLabelKey = computed(() => this.ariaLabel() || this.placeholder());

  readonly initialIdsResolved = output<readonly TItem[]>();

  /**
   * Row/chip label — a property name (e.g. `'name'`) or an accessor function.
   * Defaults to the item's string form (email field, whose items ARE strings);
   * entity subclasses `override` it. Normalized into {@link labelOf} for the
   * `<app-autocomplete [itemLabel]>` input.
   */
  readonly optionLabel: string | ((item: TItem) => string) = (item) => String(item);

  /** Normalized label accessor bound to the autocomplete primitive. */
  readonly labelOf = (item: TItem): string => {
    const label = this.optionLabel;
    return typeof label === 'function'
      ? label(item)
      : String((item as Record<string, unknown>)[label] ?? '');
  };

  /**
   * Optional magnifier (browse) button next to the autocomplete — rendered by
   * the shared `EntityMultiselectControl` template. `undefined` (the default)
   * renders no button; leaves with a browse surface (tree popover, tags dialog)
   * `override` it with their icon, tooltip key and open handler.
   */
  readonly magnifier: EntityMultiselectMagnifier | undefined = undefined;

  /**
   * Opt-in: run the search on an EMPTY query too, so focusing the field lists the whole catalogue
   * and the user can browse instead of having to remember a name to type.
   *
   * Off by default and deliberately per-leaf, never blanket: most leaves search sets far too large
   * to dump on focus, and turning it on globally would make every autocomplete in the app fetch a
   * full catalogue the moment it is tabbed into. Only override it where the set is small and
   * browsing it is the point — and make sure the leaf's API actually returns rows for an empty
   * query (several guard `query.length === 0` and return nothing).
   */
  // A getter, not a readonly field: subclasses override it with a SIGNAL-dependent expression
  // (e.g. `!this.multiple()`), which a field initialiser would freeze at construction.
  // eslint-disable-next-line @typescript-eslint/class-literal-property-style
  get browsable(): boolean {
    return false;
  }

  /**
   * Optional muted suffix for suggestion rows (parent breadcrumb, chart name, …)
   * rendered by `EntityMultiselectControl`'s shared label + suffix row; an empty
   * string renders no suffix. `undefined` (the default) keeps the autocomplete's
   * plain label rows. A structurally different row stays in the leaf template
   * and is passed via the control's `[optionTemplate]` instead.
   */
  readonly optionSuffix: ((item: TItem) => string) | undefined = undefined;

  /**
   * How wide the suggestion panel is allowed to be — forwarded to the
   * `<app-autocomplete>` by `EntityMultiselectControl`.
   *
   * `'control'` (the default) pins it to the box, which is what a list of bare
   * names wants. A leaf whose rows carry qualifiers beside the name overrides it
   * with `'content'` so the row is read rather than cropped; see the
   * `<app-autocomplete>` input for the trade.
   */
  readonly panelWidth: 'control' | 'content' = 'control';

  readonly suggestions = signal<TItem[]>([]);
  /** The selected items — two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<readonly TItem[]>([]);
  /**
   * Multi-select (chip combobox, appends) vs single-select (the next pick REPLACES). Defaults to
   * `true` — the field is used almost everywhere as a multi picker; a single-select consumer (e.g.
   * the insert-publications dialog, which inserts one publication with its own params) sets `false`.
   */
  readonly multiple = input(true);
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur so Signal Forms touched-tracking works. */
  readonly touch = output<void>();
  readonly pendingCount = this.resolving.asReadonly();
  // Reflects whether the most recent search or initial-id resolve threw.
  // Bound to the autocomplete's emptyMessage so users get an explicit
  // "Error fetching data" instead of the misleading "No results found".
  readonly fetchError = signal(false);

  private readonly searchInFlight = signal(false);
  /**
   * Whether a search is on its way — raised the moment the user types, not when
   * the request leaves.
   *
   * The 300 ms debounce plus the round trip is long enough to read, and for the
   * whole of it the panel held the previous answer or the words "No results
   * found" — a claim about a search that has not run yet, and the one reading a
   * user acts on by giving up. Bound to the autocomplete's `loading`, it shows
   * the spinner over that window instead.
   */
  readonly searching = this.searchInFlight.asReadonly();

  protected abstract searchEntities(query: string): Observable<readonly TItem[]>;
  protected abstract resolveByIds(ids: readonly TId[]): Observable<readonly TItem[]>;
  protected abstract idOf(item: TItem): TId;

  /**
   * Whether an item already carries its resolved content (label/descs) rather than
   * being an id-only stub pushed in via `[value]` on URL / named-filter restore.
   * Defaults to "any real item is resolved"; entity subclasses whose restore stubs hold
   * only an id override this so the id→entity resolve still runs — otherwise
   * `valueMatchesIds` treats the stub as already-held and the chip stays on its raw id.
   */
  protected isResolved(item: TItem): boolean {
    return item != null;
  }

  constructor() {
    let lastLang: string | undefined;
    effect(() => {
      const ids = this.initialIds();
      const lang = this.language.selectedLanguage();
      const langChanged = lastLang !== undefined && lang !== lastLang;
      lastLang = lang;
      if (!ids || ids.length === 0) {
        return;
      }
      const current = untracked(this.value);
      const valueMatchesIds =
        current.length === ids.length && current.every((item, i) => this.idOf(item) === ids[i]);
      // Skip a redundant fetch only when we already hold exactly these items, they are
      // fully RESOLVED (not id-only restore stubs), AND the language is unchanged. Entity
      // labels are backend-localized (resolveByIds sends ?lang=), so a language switch must
      // re-resolve or the field keeps stale-language labels (architecture: refetch on
      // language change). The `isResolved` check is what makes a named-filter restore —
      // which pushes id-only stubs whose ids already match — still resolve to names.
      if (valueMatchesIds && current.every((item) => this.isResolved(item)) && !langChanged) {
        return;
      }
      this.resolveIds$.next({ ids, lang });
    });

    this.search$
      .pipe(
        debounceTime(300),
        switchMap((query) =>
          query || this.browsable
            ? this.searchEntities(query).pipe(
                map((items) => ({ items, error: false as const })),
                catchError(() => of({ items: [] as readonly TItem[], error: true as const })),
              )
            : of({ items: [] as readonly TItem[], error: false as const }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(({ items, error }) => {
        this.suggestions.set([...items]);
        this.fetchError.set(error);
        this.searchInFlight.set(false);
      });

    this.resolveIds$
      .pipe(
        distinctUntilChanged((a, b) => a.ids.join(',') === b.ids.join(',') && a.lang === b.lang),
        switchMap(({ ids }) =>
          defer(() => {
            this.resolving.update((n) => n + 1);
            return this.resolveByIds(ids);
          }).pipe(
            map((items) => ({ items, error: false as const })),
            // Swallow per-request errors so the outer subscription survives a
            // transient API failure; otherwise an error here would terminate
            // resolveIds$ and silently break every subsequent initialIds change.
            catchError(() => of({ items: null, error: true as const })),
            finalize(() => this.resolving.update((n) => Math.max(0, n - 1))),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (result.error) {
          this.fetchError.set(true);
          return;
        }
        this.fetchError.set(false);
        const next = [...result.items];
        this.value.set(next);
        this.initialIdsResolved.emit(next);
      });
  }

  onSearch(query: string): void {
    // Raised only for the queries that actually reach the network — the same test
    // the pipeline below switches on. A field that does not browse answers an
    // empty query with an empty list without asking anybody, and a spinner over
    // that is a wait that is not happening; worse, `loading` is one of the things
    // that opens the panel, so an emptied box would flash a panel open to show it.
    this.searchInFlight.set(!!query || this.browsable);
    this.search$.next(query);
  }

  /** Bridge the primitive's `(touched)` (blur) to Signal Forms touched-tracking. */
  markTouched(): void {
    this.touch.emit();
  }

  onSelect(value: readonly TItem[] | null): void {
    const next = value ? [...value] : [];
    // Setting the `value` model emits `valueChange`.
    this.value.set(next);
    // Clear the suggestion list on every commit so a fresh search starts clean;
    // the value the control holds is the source of truth for the rendered chips
    // (this is what lets a search pick coexist with a modal/`appendOne` pick).
    this.suggestions.set([]);
    this.touch.emit();
  }

  onClear(): void {
    this.value.set([]);
    this.touch.emit();
  }

  /**
   * Append a single item to the current value, deduplicated by `idOf(item)`,
   * emitting `valueChange` + `touch`. Used by magnifier flows (tag and
   * area/category pickers) where a dialog returns a single picked entity that
   * should be added to the multi-select.
   */
  appendOne(item: TItem): void {
    const current = this.value();
    const itemId = this.idOf(item);
    if (current.some((existing) => this.idOf(existing) === itemId)) return;
    const next = [...current, item];
    this.value.set(next);
    // See onSelect: the control value owns the chips; clear stale search suggestions.
    this.suggestions.set([]);
    this.touch.emit();
  }
}
