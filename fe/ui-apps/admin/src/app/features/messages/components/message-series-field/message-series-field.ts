import { Component, computed, effect, inject, input, model, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
import { Observable, Subject, catchError, distinctUntilChanged, map, of, switchMap } from 'rxjs';

import { Autocomplete } from '../../../../shared/forms/autocomplete/autocomplete';
import { AutocompleteSearchField } from '../../../../shared/forms/autocomplete-search-field';
import { MessageSeries } from '../../models/message.model';
import { MessageSeriesApiService } from '../../services/api/message-series-api.service';

@Component({
  selector: 'app-message-series-field',
  templateUrl: './message-series-field.html',
  imports: [Autocomplete, TranslocoPipe],
})
export class MessageSeriesField
  extends AutocompleteSearchField<MessageSeries>
  implements FormValueControl<MessageSeries | readonly MessageSeries[] | null>
{
  private readonly seriesApi = inject(MessageSeriesApiService);
  private readonly resolveIds$ = new Subject<string[]>();

  readonly multiple = input(false);
  readonly domainId = input<string | null>(null);
  readonly initialIds = input<string[] | null>(null);
  readonly placeholder = input('');

  readonly initialIdsResolved = output<MessageSeries[]>();

  /** Two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<MessageSeries | readonly MessageSeries[] | null>(null);

  /** Chip label accessor for `<app-autocomplete>`. */
  readonly seriesLabel = (series: MessageSeries): string => series.seriesId;

  /** Value normalized to an array for the (always-array) autocomplete primitive. */
  readonly valueItems = computed<readonly MessageSeries[]>(() => {
    const current = this.value();
    if (current == null) return [];
    return Array.isArray(current) ? current : [current];
  });

  constructor() {
    super();
    effect(() => {
      const ids = this.initialIds();
      if (ids && ids.length > 0) {
        this.resolveIds$.next(ids);
      }
    });

    // switchMap cancels in-flight requests when initialIds changes, preventing stale overwrites.
    // distinctUntilChanged avoids redundant API calls when the same IDs are re-emitted.
    this.resolveIds$
      .pipe(
        distinctUntilChanged((a, b) => a.join(',') === b.join(',')),
        switchMap((ids) =>
          this.seriesApi.getMessageSeriesByIds(ids).pipe(
            map((items) => ({ items, error: false as const })),
            catchError(() => of({ items: null, error: true as const })),
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
        const series = result.items;
        if (this.multiple()) {
          this.value.set(series);
        } else if (series.length > 0) {
          this.value.set(series[0]);
        }
        this.initialIdsResolved.emit(series);
      });
  }

  protected searchApi(query: string): Observable<readonly MessageSeries[]> {
    return this.seriesApi.searchMessageSeries(query, { domainId: this.domainId() ?? undefined });
  }

  /** Bridge the primitive's `(select)` (an item array) to the single/multi value shape. */
  onSelectItems(items: readonly MessageSeries[]): void {
    this.onSelect(this.multiple() ? [...items] : (items[0] ?? null));
  }

  onSelect(value: MessageSeries | readonly MessageSeries[] | null): void {
    this.value.set(value);
    this.touch.emit();
  }

  onClear(): void {
    this.value.set(this.multiple() ? [] : null);
    this.touch.emit();
  }
}
