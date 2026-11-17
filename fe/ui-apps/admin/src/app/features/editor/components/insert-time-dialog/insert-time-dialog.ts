import { Component, computed, inject, input, model, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import {
  translateDateInterval,
  type DateIntervalTranslation,
} from '../../../../shared/utils/translate-date-interval';
import type { DateInterval, Message } from '../../../messages/models/message.model';
import { readFormatTimeSettings, writeFormatTimeSettings } from './format-time-prefs';

/**
 * The Insert-Time dialog. Purely CLIENT-SIDE — it formats the invoking part's
 * `eventDates` into an HTML fragment for the Description editor, no backend call.
 *
 * A part radio picker appears only for a multi-part message (preselecting the
 * invoking part); the selected part's intervals render as a checkbox list (a sole
 * interval auto-selects). The live preview is `translateDateInterval` per checked
 * interval — resolved via Transloco IN THE TARGET model language `lang`,
 * joined with `<br>`. An empty
 * selection (or a part with no intervals) previews the "until further notice"
 * phrase. The "Include time zone" toggle (default on) and its value round-trip
 * through `localStorage['formatTimeSettings']`. Insert emits the HTML and closes.
 */
@Component({
  selector: 'app-insert-time-dialog',
  imports: [TranslocoPipe, Dialog],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="onOpened()"
      [modal]="true"
      [header]="'editor.dialog.time.header' | transloco"
      [style]="{ width: '32rem' }"
    >
      <!-- Gated on visible(): the projected content is instantiated even while the dialog is
           closed, and the interval phrases must never compute before open() supplies the lang. -->
      @if (visible()) {
        <div class="flex flex-col gap-4">
          @if (parts().length > 1) {
            <div class="flex flex-wrap items-center gap-3">
              <span class="text-sm font-medium">{{ 'editor.dialog.time.part' | transloco }}</span>
              @for (part of parts(); track $index) {
                <label class="flex items-center gap-1.5 text-sm">
                  <input
                    type="radio"
                    class="radio"
                    name="insert-time-part"
                    [checked]="selectedPartIndex() === $index"
                    (change)="selectPart($index)"
                  />
                  <span>#{{ $index + 1 }}</span>
                </label>
              }
            </div>
          }

          <div class="flex flex-col gap-1">
            <div class="flex items-center justify-between border-b border-surface-200 pb-1">
              <span class="text-sm font-medium">{{
                'editor.dialog.time.intervals' | transloco
              }}</span>
              @if (intervals().length > 0) {
                <span class="text-xs text-fg-subtle">
                  {{ 'editor.dialog.time.select' | transloco }}
                  <button type="button" class="link-button is-subtle" (click)="selectAll(true)">
                    {{ 'editor.dialog.time.all' | transloco }}
                  </button>
                  /
                  <button type="button" class="link-button is-subtle" (click)="selectAll(false)">
                    {{ 'editor.dialog.time.none' | transloco }}
                  </button>
                </span>
              }
            </div>
            @for (label of intervalLabels(); track $index) {
              <label class="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  class="checkbox"
                  [checked]="selectedIntervals().has($index)"
                  (change)="toggleInterval($index)"
                />
                <span>{{ label }}</span>
              </label>
            } @empty {
              <p class="py-1 text-sm text-fg-subtle">
                {{ 'editor.dialog.time.noIntervals' | transloco }}
              </p>
            }
          </div>

          <label class="flex items-center gap-2 text-sm">
            <input
              id="insert-time-tz"
              type="checkbox"
              class="checkbox"
              [checked]="includeTimeZone()"
              (change)="toggleTimeZone($any($event.target).checked)"
            />
            <span>{{ 'editor.dialog.time.includeTimeZone' | transloco }}</span>
          </label>

          <div class="flex flex-col gap-1 border-t border-surface-200 pt-3">
            <span class="text-sm font-medium">{{ 'editor.dialog.time.result' | transloco }}</span>
            <div
              class="prose-sm max-w-none rounded border border-surface-200 p-2"
              [innerHTML]="preview()"
            ></div>
          </div>
        </div>
      }

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" (click)="insert()">
          {{ 'editor.dialog.time.insert' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class InsertTimeDialog {
  readonly visible = model(false);
  /** The message whose parts supply the interval lists (facade-owned, read-only). */
  readonly message = input<Message | undefined>(undefined);
  /** The invoking part index — preselected. */
  readonly partIndex = input<number>(0);
  /** The target model language the phrases resolve in and the fragment is inserted into. */
  readonly lang = input<string>('');
  /** The formatted HTML fragment, emitted on Insert. */
  readonly insertContent = output<string>();

  private readonly transloco = inject(TranslocoService);
  private readonly languageService = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);

  protected readonly selectedPartIndex = signal(0);
  protected readonly includeTimeZone = signal(true);
  protected readonly selectedIntervals = signal<ReadonlySet<number>>(new Set());
  // Bumped once the target-language bundle has loaded so the preview re-resolves.
  private readonly langLoaded = signal(0);

  protected readonly parts = computed(() => this.message()?.parts ?? []);

  protected readonly intervals = computed<readonly DateInterval[]>(
    () => this.parts()[this.selectedPartIndex()]?.eventDates ?? [],
  );

  private readonly locale = computed<string>(() => {
    // Fall back to the app language when the invoking language is not (yet) supplied — an
    // empty locale must never reach Intl (RangeError aborts the whole render pass).
    const lang = this.lang() || this.languageService.selectedLanguage();
    const configured = this.languageService.modelLanguages.find((l) => l.value === lang);
    return configured?.locale ?? lang;
  });

  /** Each interval's own phrase — the checkbox-row labels (reflect the tz toggle). */
  protected readonly intervalLabels = computed<readonly string[]>(() =>
    this.intervals().map((interval) =>
      this.resolve(translateDateInterval(interval, this.options())),
    ),
  );

  /** Live preview: checked intervals joined by `<br>` (a separator BETWEEN intervals, so a single
   * interval has no trailing break / blank line under it), or the until-further-notice phrase. */
  protected readonly preview = computed<string>(() => {
    const intervals = this.intervals();
    const selected = this.selectedIntervals();
    const checked = intervals.filter((_, index) => selected.has(index));
    if (checked.length === 0) {
      return this.resolve(translateDateInterval(null, this.options()));
    }
    return checked
      .map((interval) => this.resolve(translateDateInterval(interval, this.options())))
      .join('<br>');
  });

  protected onOpened(): void {
    const parts = this.parts();
    const index = Math.min(Math.max(0, this.partIndex()), Math.max(0, parts.length - 1));
    this.selectedPartIndex.set(index);
    this.includeTimeZone.set(readFormatTimeSettings().tz);
    this.autoSelect();
    // Load the target model-language bundle so the phrases resolve in that language
    // even when it is not the active UI language.
    this.transloco.load(this.lang()).subscribe(() => this.langLoaded.update((v) => v + 1));
  }

  protected selectPart(index: number): void {
    this.selectedPartIndex.set(index);
    this.autoSelect();
  }

  protected toggleInterval(index: number): void {
    this.selectedIntervals.update((selected) => {
      const next = new Set(selected);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }

  protected toggleTimeZone(checked: boolean): void {
    this.includeTimeZone.set(checked);
  }

  protected selectAll(select: boolean): void {
    this.selectedIntervals.set(
      select ? new Set(this.intervals().map((_, index) => index)) : new Set(),
    );
  }

  protected insert(): void {
    writeFormatTimeSettings({ tz: this.includeTimeZone() });
    this.insertContent.emit(this.preview());
    this.visible.set(false);
  }

  /** Auto-select a sole interval (a single interval is checked automatically). */
  private autoSelect(): void {
    const count = this.intervals().length;
    this.selectedIntervals.set(count === 1 ? new Set([0]) : new Set());
  }

  private options() {
    return {
      locale: this.locale(),
      includeTimeZone: this.includeTimeZone(),
      timeZone: this.localeFormat.sessionTimeZone(),
    };
  }

  private resolve(translation: DateIntervalTranslation): string {
    this.langLoaded();
    return this.transloco.translate(translation.key, translation.params, this.lang());
  }
}
