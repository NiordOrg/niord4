import { Component, computed, inject, model, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { ModelLanguagePicker } from '../../../components/model-language-picker/model-language-picker';
import { Select } from '../../../../../shared/forms/select/select';
import {
  DictionariesApiService,
  DictionaryEntry,
} from '../../../services/api/dictionaries-api.service';

/**
 * LIST param-type value editor: a two-pane transfer list. The right pane is the
 * ordered selection (`values`, reorderable); the left pane is the chosen
 * dictionary's entries MINUS the already-selected (deduped by entry id). Add
 * appends, remove restores, Clear empties. `(valuesChanged)` fires after any edit.
 */
@Component({
  selector: 'app-list-values-editor',
  imports: [Icon, Select, FormsModule, TranslocoPipe, ModelLanguagePicker],
  templateUrl: './list-values-editor.html',
  host: { class: 'flex min-h-0 flex-1 flex-col' },
})
export class ListValuesEditor {
  private readonly dictionariesApi = inject(DictionariesApiService);
  protected readonly modelLanguage =
    inject(ModelLanguageSelection, { optional: true }) ?? new ModelLanguageSelection();

  readonly values = model<DictionaryEntry[]>([]);
  readonly valuesChanged = output<void>();

  private readonly names = toSignal(
    this.dictionariesApi.getDictionaryNames().pipe(catchError(() => of<string[]>([]))),
    { initialValue: [] as string[] },
  );
  protected readonly dictionaryNames = computed(() => [...this.names()]);

  /** Requested dictionary (defaults to 'template'); falls back to the first available. */
  private readonly requestedName = signal('template');
  protected readonly currentDictionaryName = computed(() => {
    const names = this.names();
    const requested = this.requestedName();
    return names.length === 0 || names.includes(requested) ? requested : names[0];
  });

  protected readonly dictSearch = signal('');

  private readonly entries = toSignal(
    toObservable(this.currentDictionaryName).pipe(
      switchMap((name) =>
        name
          ? this.dictionariesApi
              .getDictionaryEntries(name)
              .pipe(catchError(() => of<DictionaryEntry[]>([])))
          : of<DictionaryEntry[]>([]),
      ),
    ),
    { initialValue: [] as DictionaryEntry[] },
  );

  // Dedupe on the natural `key` (non-optional), not the optional numeric `id`.
  private readonly selectedKeys = computed(() => new Set(this.values().map((value) => value.key)));

  /** Available = current dictionary entries − selected (by key), key-filtered. */
  protected readonly available = computed(() => {
    const selected = this.selectedKeys();
    const term = this.dictSearch().trim().toLowerCase();
    return this.entries()
      .filter((entry) => !selected.has(entry.key))
      .filter((entry) => term === '' || entry.key.toLowerCase().includes(term));
  });

  protected readonly selectedRows = computed(() => [...this.values()]);

  protected onDictionaryChange(name: string): void {
    this.requestedName.set(name);
    this.dictSearch.set('');
  }

  protected addValue(entry: DictionaryEntry): void {
    this.values.set([...this.values(), entry]);
    this.valuesChanged.emit();
  }

  protected removeValue(entry: DictionaryEntry): void {
    this.values.set(this.values().filter((value) => value.key !== entry.key));
    this.valuesChanged.emit();
  }

  protected clearAll(): void {
    this.values.set([]);
    this.valuesChanged.emit();
  }

  protected moveUp(index: number): void {
    if (index > 0) {
      this.swap(index, index - 1);
    }
  }

  protected moveDown(index: number): void {
    if (index < this.values().length - 1) {
      this.swap(index, index + 1);
    }
  }

  private swap(a: number, b: number): void {
    const next = [...this.values()];
    [next[a], next[b]] = [next[b], next[a]];
    this.values.set(next);
    this.valuesChanged.emit();
  }
}
