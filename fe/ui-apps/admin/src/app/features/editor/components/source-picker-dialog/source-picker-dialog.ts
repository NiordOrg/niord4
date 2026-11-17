import { Component, computed, DestroyRef, inject, model, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { forkJoin } from 'rxjs';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { DatePicker } from '../../../../shared/forms/date-picker/date-picker';
import { Icon } from '../../../../shared/icons/icon';
import {
  composeSourceText,
  type ComposeSourceLanguage,
  type SelectedSource,
} from '../../../../shared/utils/compose-source-text';
import { SourcesApiService, type Source } from '../../../messages/services/api/sources-api.service';

interface SelectedEntry {
  readonly source: Source;
  readonly date: Date | null;
}

/**
 * The "Add sources…" dialog. Lists the catalogue's sources, filters them
 * client-side by name/abbreviation, and lets the user multi-select and give each
 * an optional date. On Add it fetches every selected source's all-language descs
 * (`getSource`), composes per-language source text for every configured model
 * language (`composeSourceText`), and emits it via `sourcesAdded` for the host to append.
 *
 * The `/sources/all` adapter returns every source with an `active` flag rather than filtering
 * server-side, so the active-only filter is applied client-side here.
 *
 * A source date is picked in, and printed in, the session domain's zone — the picker resolves
 * the day and the composer is told the same zone, so the two ends cannot disagree. With no
 * session domain there is no such zone, and Add refuses rather than printing a day resolved in
 * whichever zone the editing desk happens to sit in.
 */
@Component({
  selector: 'app-source-picker-dialog',
  imports: [TranslocoPipe, Dialog, DatePicker, Icon],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="onOpened()"
      [modal]="true"
      [header]="'editor.dialog.source.header' | transloco"
      [style]="{ width: '40rem' }"
    >
      <div class="flex flex-col gap-4">
        <div class="relative w-72">
          <input
            type="text"
            class="w-full text-input"
            [value]="filter()"
            (input)="filter.set($any($event.target).value)"
            [placeholder]="'editor.dialog.source.filter' | transloco"
            [attr.aria-label]="'editor.dialog.source.filter' | transloco"
            autocomplete="off"
          />
          @if (filter() !== '') {
            <button
              type="button"
              class="absolute top-1/2 right-2 -translate-y-1/2 text-fg-subtle hover:text-fg"
              [attr.aria-label]="'common.clear' | transloco"
              (click)="filter.set('')"
            >
              <app-icon name="lucideX" class="text-xs" />
            </button>
          }
        </div>

        <div
          class="max-h-64 overflow-y-auto rounded border border-surface-200 dark:border-surface-700"
        >
          <table class="w-full text-sm">
            <thead class="sticky top-0 bg-surface-50 dark:bg-surface-800">
              <tr class="text-left text-fg-subtle">
                <th class="w-8 px-2 py-1.5"></th>
                <th class="px-2 py-1.5">{{ 'editor.dialog.source.column.name' | transloco }}</th>
                <th class="px-2 py-1.5">
                  {{ 'editor.dialog.source.column.abbreviation' | transloco }}
                </th>
              </tr>
            </thead>
            <tbody>
              @for (source of filtered(); track source.id) {
                <tr class="border-t border-surface-100 dark:border-surface-800">
                  <td class="px-2 py-1.5 leading-[0]">
                    <label class="inline-flex">
                      <input
                        type="checkbox"
                        class="h-4 w-4"
                        [checked]="selectedIds().has(source.id!)"
                        (change)="toggle(source)"
                        [attr.aria-label]="label(source)"
                      />
                    </label>
                  </td>
                  <td class="px-2 py-1.5">{{ label(source) }}</td>
                  <td class="px-2 py-1.5 text-fg-subtle">{{ abbreviation(source) }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="3" class="px-2 py-3 text-center text-fg-subtle">
                    {{ 'editor.dialog.source.empty' | transloco }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (selection().length > 0) {
          <div class="flex flex-col gap-1 border-t border-surface-200 pt-3 dark:border-surface-700">
            <div class="text-sm font-medium">
              {{ 'editor.dialog.source.selection' | transloco }}
            </div>
            <table class="w-full text-sm">
              <tbody>
                @for (entry of selection(); track entry.source.id) {
                  <tr>
                    <td class="py-1.5 pr-2 align-middle">{{ label(entry.source) }}</td>
                    <td class="w-44 py-1.5 pr-2 align-middle">
                      <app-date-picker
                        [value]="entry.date"
                        (valueChange)="setDate(entry.source.id!, $event)"
                        [placeholder]="'editor.dialog.source.date' | transloco"
                      />
                    </td>
                    <td class="w-8 py-1.5 align-middle leading-[0]">
                      <button
                        type="button"
                        class="icon-button hover:text-danger"
                        [attr.aria-label]="'editor.dialog.source.remove' | transloco"
                        (click)="deselect(entry.source.id!)"
                      >
                        <app-icon name="lucideTrash2" />
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="btn-primary"
          [disabled]="selection().length === 0 || adding()"
          (click)="add()"
        >
          {{ 'editor.dialog.source.add' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class SourcePickerDialog {
  readonly visible = model(false);
  /** Composed per-language source text for the host to append into its `sources` form slice. */
  readonly sourcesAdded = output<Record<string, string>>();

  private readonly sourcesApi = inject(SourcesApiService);
  private readonly languageService = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly transloco = inject(TranslocoService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly filter = signal('');
  protected readonly adding = signal(false);
  private readonly sources = signal<readonly Source[]>([]);
  protected readonly selection = signal<readonly SelectedEntry[]>([]);

  protected readonly selectedIds = computed(
    () => new Set(this.selection().map((entry) => entry.source.id)),
  );

  protected readonly filtered = computed<readonly Source[]>(() => {
    const term = this.filter().trim().toLowerCase();
    return this.sources().filter((source) => {
      if (!source.active) {
        return false;
      }
      if (term === '') {
        return true;
      }
      return (
        this.label(source).toLowerCase().includes(term) ||
        this.abbreviation(source).toLowerCase().includes(term)
      );
    });
  });

  protected label(source: Source): string {
    return source.descs[0]?.name ?? '';
  }

  protected abbreviation(source: Source): string {
    return source.descs[0]?.abbreviation ?? '';
  }

  protected onOpened(): void {
    this.filter.set('');
    this.selection.set([]);
    this.adding.set(false);
    this.sources.set([]);
    this.sourcesApi
      .getAll(this.languageService.selectedLanguage())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sources) => this.sources.set(sources),
        error: () => this.notifications.error('editor.dialog.source.loadError'),
      });
  }

  protected toggle(source: Source): void {
    this.selection.update((selection) =>
      selection.some((entry) => entry.source.id === source.id)
        ? selection.filter((entry) => entry.source.id !== source.id)
        : [...selection, { source, date: null }],
    );
  }

  protected setDate(id: number, date: Date | null): void {
    this.selection.update((selection) =>
      selection.map((entry) => (entry.source.id === id ? { ...entry, date } : entry)),
    );
  }

  protected deselect(id: number): void {
    this.selection.update((selection) => selection.filter((entry) => entry.source.id !== id));
  }

  protected add(): void {
    const entries = this.selection();
    if (entries.length === 0) {
      return;
    }
    const timeZone = this.localeFormat.sessionTimeZone();
    if (!timeZone) {
      this.notifications.error('common.dateTime.noTimeZone');
      return;
    }
    this.adding.set(true);

    const languages = this.languageService.modelLanguages;
    forkJoin({
      details: forkJoin(entries.map((entry) => this.sourcesApi.getSource(entry.source.id!))),
      // Load every model-language bundle so the localized "and" joiner resolves for
      // languages other than the active one (N-language support).
      bundles: forkJoin(languages.map((language) => this.transloco.load(language.value))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ details }) => {
          const detailById = new Map(details.map((source) => [source.id, source]));
          const composeSources: SelectedSource[] = entries.map((entry) => ({
            descs: detailById.get(entry.source.id)?.descs ?? entry.source.descs,
            date: entry.date,
          }));
          const composeLanguages: ComposeSourceLanguage[] = languages.map((language) => ({
            lang: language.value,
            locale: language.locale,
            and: this.transloco.translate('term.and', {}, language.value),
          }));

          this.sourcesAdded.emit(composeSourceText(composeSources, composeLanguages, { timeZone }));
          this.adding.set(false);
          this.visible.set(false);
        },
        error: () => {
          this.adding.set(false);
          this.notifications.error('editor.dialog.source.addError');
        },
      });
  }
}
