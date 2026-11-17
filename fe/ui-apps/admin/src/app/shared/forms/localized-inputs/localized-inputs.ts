import { Component, computed, inject, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { combineLatest, map, of, switchMap } from 'rxjs';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { LanguageFlag } from '../../components/language-flag/language-flag';

/** Payload of a per-keystroke edit: the language whose input changed + its new value. */
export interface LocalizedValueInput {
  readonly lang: string;
  readonly value: string;
}

/**
 * A group of per-language text inputs — one input per configured model language, each with its
 * language flag painted inside the field. A display/value primitive with no message-model
 * knowledge: the caller feeds a `Record<lang, string>` and re-applies it on each `(valueInput)`
 * (controlled-component pattern), so the same group serves message descriptions, geometry feature
 * names, and any future per-language text.
 *
 * Sibling: {@link LocalizedTextInput} (`admin/components/`) is the single-language Signal-Forms
 * (`FormValueControl`) variant for `[formField]`-driven admin editors. The two are intentionally
 * NOT merged (different binding models) but render the SAME flag-inside field — keep the flag
 * `<img>` + `pl-9` markup in sync across both if you restyle one.
 *
 * The rendered languages come from {@link LanguageService.modelLanguages}
 * by default and always scale to the configured list — a two-language
 * deployment is a coincidence, not a contract. Pass `languages` to override the
 * set (restricted contexts, tests).
 *
 * `disabled` locks the whole group (the autoTitle case); `(inputFocus)` fires
 * with the focused language for the vicinity copy-on-focus hook.
 */
@Component({
  selector: 'app-localized-inputs',
  imports: [LanguageFlag, TranslocoPipe],
  template: `
    <div
      [class]="
        stacked()
          ? 'grid gap-2.5'
          : 'grid gap-2.5 sm:grid-cols-[repeat(auto-fit,minmax(12rem,1fr))]'
      "
    >
      @for (item of items(); track item.lang) {
        <!-- The language flag is painted INSIDE the field (matching the admin editors) instead of a
             separate badge above; the input's pl-9 reserves room for it. The flag img is decorative
             (empty alt) — the field's aria-label already names its language. -->
        <span class="relative block">
          <app-language-flag
            class="pointer-events-none absolute top-2.75 left-2.5 h-3.5 w-5 rounded-xs"
            [lang]="item.lang"
            [flagPath]="item.flagPath"
          />
          @if (multiline()) {
            <textarea
              class="w-full pl-9 text-input"
              rows="2"
              [id]="item.inputId"
              [value]="valueFor(item.lang)"
              [disabled]="disabled()"
              [placeholder]="placeholderFor(item.lang)"
              [attr.aria-label]="
                'shared.forms.localizedInputs.inputAria' | transloco: { language: item.label }
              "
              (input)="emit(item.lang, $event)"
              (focus)="inputFocus.emit(item.lang)"
              (blur)="inputBlur.emit(item.lang)"
            ></textarea>
          } @else {
            <input
              type="text"
              class="w-full pl-9 text-input"
              [id]="item.inputId"
              [value]="valueFor(item.lang)"
              [disabled]="disabled()"
              [placeholder]="placeholderFor(item.lang)"
              [attr.aria-label]="
                'shared.forms.localizedInputs.inputAria' | transloco: { language: item.label }
              "
              (input)="emit(item.lang, $event)"
              (focus)="inputFocus.emit(item.lang)"
              (blur)="inputBlur.emit(item.lang)"
            />
          }
        </span>
      }
    </div>
  `,
  host: { class: 'contents' },
})
export class LocalizedInputs {
  private readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  /** Model languages to render (one input each). Defaults to the configured list. */
  readonly languages = input<readonly string[] | undefined>(undefined);
  /** Per-language values keyed by language code; missing entries render empty. */
  readonly value = input<Record<string, string>>({});
  /** Locks every input in the group (the autoTitle case). */
  readonly disabled = input(false);
  /** Render textareas instead of single-line inputs. */
  readonly multiline = input(false);
  /** Stack the per-language inputs vertically (one column) instead of the default 2-column grid. */
  readonly stacked = input(false);
  /** Static placeholder shown in EVERY input (already translated by the caller). Overridden
   * per-row by {@link placeholderKey} when that is set. */
  readonly placeholder = input('');
  /**
   * Translation key resolved into each row's OWN language, so the DA input hints in Danish
   * ("Omegn") and the EN input in English ("Vicinity"). Takes precedence over {@link placeholder}.
   * Uses `selectTranslate`, which loads the per-language bundle on demand and re-emits, so the
   * hint appears even for a language whose bundle wasn't the active one.
   */
  readonly placeholderKey = input('');
  /** Optional id prefix so a parent `<label for>` can target `${prefix}-${lang}`. */
  readonly inputIdPrefix = input<string | undefined>(undefined);

  /** Emitted per keystroke with the language + its new value. */
  readonly valueInput = output<LocalizedValueInput>();
  /** Emitted when an input gains focus (vicinity copy-on-focus hook). */
  readonly inputFocus = output<string>();
  /** Fires on input blur with the language — hosts use it to flush debounced work. */
  readonly inputBlur = output<string>();

  private readonly effectiveLanguages = computed(
    () => this.languages() ?? this.language.modelLanguages.map((option) => option.value),
  );

  protected readonly items = computed(() => {
    const prefix = this.inputIdPrefix();
    return this.effectiveLanguages().map((lang) => {
      const option = this.language.modelLanguages.find((entry) => entry.value === lang);
      return {
        lang,
        code: lang.toUpperCase(),
        // Human language label ("Dansk") — the accessible name, not the raw code.
        label: option?.label ?? lang,
        flagPath: option?.flagPath,
        inputId: prefix ? `${prefix}-${lang}` : undefined,
      };
    });
  });

  /**
   * Per-language placeholders keyed by language code, resolved from {@link placeholderKey} in each
   * row's own language. `selectTranslate` loads the target-language bundle on demand and re-emits,
   * so the map fills in reactively as bundles arrive. Empty when no `placeholderKey` is set.
   */
  private readonly placeholderMap = toSignal(
    toObservable(
      computed(() => ({ key: this.placeholderKey(), langs: this.effectiveLanguages() })),
    ).pipe(
      switchMap(({ key, langs }) => {
        if (!key || langs.length === 0) {
          return of<Record<string, string>>({});
        }
        return combineLatest(
          langs.map((lang) =>
            this.transloco
              .selectTranslate<string>(key, undefined, lang)
              .pipe(map((value) => [lang, value] as const)),
          ),
        ).pipe(map((pairs) => Object.fromEntries(pairs)));
      }),
    ),
    { initialValue: {} as Record<string, string> },
  );

  protected valueFor(lang: string): string {
    return this.value()[lang] ?? '';
  }

  /** The placeholder for one row: its own-language `placeholderKey` translation, else the static one. */
  protected placeholderFor(lang: string): string {
    return this.placeholderKey() ? (this.placeholderMap()[lang] ?? '') : this.placeholder();
  }

  protected emit(lang: string, event: Event): void {
    this.valueInput.emit({
      lang,
      value: (event.target as HTMLInputElement | HTMLTextAreaElement).value,
    });
  }
}
