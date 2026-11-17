import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  untracked,
  viewChild,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { of, switchMap } from 'rxjs';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Menu } from '../../../../shared/components/menu/menu';
import { MenuTriggerFor } from '../../../../shared/components/menu/menu-trigger.directive';
import {
  insertToken,
  type FormatToken,
} from '../../../../shared/forms/format-token-input/token-insert';
import { Icon } from '../../../../shared/icons/icon';
import type { MenuItem } from '../../../../shared/models/menu-item.model';
import { LanguageFlag } from '../../../../shared/components/language-flag/language-flag';

/**
 * One per-language text control with the language flag painted inside the
 * field. Consumers render one instance per entry in the configured
 * model-language list — the component itself carries no assumption about how
 * many languages exist. Bind it with Signal Forms' `[formField]`.
 *
 * `multiline` switches the control to a textarea (dictionary values); the
 * single-line variant covers names/abbreviations. `monospace` is for fields
 * holding a PATTERN rather than prose — a file-name or citation template whose
 * `{{token}}` placeholders have to be read character by character.
 *
 * `tokens` turns such a pattern field into one with an insert menu: the
 * vocabulary a pattern accepts is finite and nobody remembers it, so the field
 * offers it rather than leaving the writer to copy a token out of a hint. Empty
 * by default, and an empty list renders no button at all — a prose field has no
 * vocabulary to offer.
 *
 * Sibling: {@link LocalizedInputs} (`shared/forms/`) is the controlled multi-language variant used
 * by the message editor. The two are intentionally NOT merged (different binding models) but render
 * the SAME flag-inside field — keep the flag `<img>` + `pl-9` markup in sync across both.
 */
@Component({
  selector: 'app-localized-text-input',
  imports: [LanguageFlag, Icon, Menu, MenuTriggerFor, TranslocoPipe],
  template: `
    <span [class]="wrapperClass()">
      <span class="relative block flex-1">
        <app-language-flag
          class="pointer-events-none absolute top-2.75 left-2.5 h-3.5 w-5 rounded-xs"
          [lang]="lang()"
          [flagPath]="flagPath()"
        />
        @if (multiline()) {
          <textarea
            #field
            [class]="fieldClass()"
            rows="2"
            [id]="inputId()"
            [value]="value()"
            [disabled]="disabled()"
            [placeholder]="placeholderText()"
            [attr.aria-label]="
              ariaLabelKey() ? (ariaLabelKey() ?? '' | transloco) : languageLabel()
            "
            (input)="onInput($any($event.target).value)"
            (focus)="onFieldFocus()"
            (blur)="touch.emit()"
          ></textarea>
        } @else {
          <input
            #field
            type="text"
            [class]="fieldClass()"
            [id]="inputId()"
            [value]="value()"
            [disabled]="disabled()"
            [placeholder]="placeholderText()"
            [attr.aria-label]="
              ariaLabelKey() ? (ariaLabelKey() ?? '' | transloco) : languageLabel()
            "
            (input)="onInput($any($event.target).value)"
            (focus)="onFieldFocus()"
            (blur)="touch.emit()"
          />
        }
      </span>

      @if (tokenItems().length > 0) {
        <button
          type="button"
          class="btn-secondary shrink-0"
          [disabled]="disabled()"
          [appMenuTriggerFor]="tokenMenu"
          align="end"
        >
          {{ 'shared.forms.formatTokenInput.insert' | transloco }}
          <app-icon name="lucideChevronDown" class="ml-1 text-xs opacity-70" />
        </button>
        <app-menu
          #tokenMenu
          [model]="tokenItems()"
          [ariaLabel]="'shared.forms.formatTokenInput.menuAria' | transloco"
        />
      }
    </span>
  `,
})
export class LocalizedTextInput implements FormValueControl<string> {
  private readonly languageSettings = inject(LanguageService);
  private readonly injector = inject(Injector);
  private readonly transloco = inject(TranslocoService);

  readonly lang = input.required<string>();
  readonly multiline = input(false);
  /** Render the value in the mono face — for pattern fields, not prose. */
  readonly monospace = input(false);
  readonly inputId = input<string | undefined>(undefined);
  /** Transloco key for the accessible name; the component translates it itself. */
  readonly ariaLabelKey = input<string | undefined>(undefined);
  /**
   * Translation key for the placeholder, resolved in the row's OWN language — so a row for
   * Danish hints in Danish and the English one in English, whichever language the operator is
   * running the app in. Empty renders no placeholder.
   */
  readonly placeholderKey = input('');
  /** The vocabulary the insert menu offers. Empty renders no menu. */
  readonly tokens = input<readonly FormatToken[]>([]);

  /** The field value (two-way bound by `[formField]`). */
  readonly value = model<string>('');
  /** Emitted on blur so touched-tracking and `debounce('blur')` work. */
  readonly touch = output<void>();
  /** Set by the form when the field is disabled. */
  readonly disabled = input<boolean>(false);

  private readonly field = viewChild<ElementRef<HTMLInputElement | HTMLTextAreaElement>>('field');

  /**
   * Whether the field has held the caret for the CURRENT text.
   *
   * A text input reports `selectionStart: 0` before it was ever focused, so the
   * selection alone cannot tell "caret at the start" from "never touched" — and
   * an untouched field must append rather than insert at position 0.
   */
  private hasCaret = false;
  /** The last value this control wrote, so an outside write can be spotted. */
  private lastInternalValue: string | null = null;

  constructor() {
    // A value written from outside — the editor opening on another series, a
    // reset — means the caret no longer belongs to the text in the field.
    effect(() => {
      const value = this.value();
      untracked(() => {
        if (value !== this.lastInternalValue) {
          this.lastInternalValue = value;
          this.hasCaret = false;
        }
      });
    });
  }

  /**
   * The placeholder resolved from {@link placeholderKey} in this row's language. `selectTranslate`
   * loads the target-language bundle on demand and re-emits, so the hint appears even for a
   * language whose bundle was not the active one.
   */
  protected readonly placeholderText = toSignal(
    toObservable(computed(() => ({ key: this.placeholderKey(), lang: this.lang() }))).pipe(
      switchMap(({ key, lang }) =>
        key ? this.transloco.selectTranslate<string>(key, undefined, lang) : of(''),
      ),
    ),
    { initialValue: '' },
  );

  protected readonly tokenItems = computed<readonly MenuItem[]>(() =>
    this.tokens().map(({ token, label }) => ({
      label,
      command: () => this.insert(token),
    })),
  );

  // Concatenated into ONE string: Angular's array `[class]` form drops
  // space-separated entries, and a static `class` beside the binding is replaced
  // the moment the binding evaluates.
  protected readonly fieldClass = computed(
    () => 'w-full pl-9 text-input' + (this.monospace() ? ' font-mono' : ''),
  );

  /**
   * The field alone, or the field beside its insert button.
   *
   * `block` without the menu, so a control that has always rendered as one
   * element keeps doing so wherever it is already laid out.
   */
  protected readonly wrapperClass = computed(() =>
    this.tokenItems().length > 0 ? 'flex items-start gap-2' : 'block',
  );

  private readonly languageOption = computed(() =>
    this.languageSettings.modelLanguages.find((language) => language.value === this.lang()),
  );

  protected readonly flagPath = computed(() => this.languageOption()?.flagPath);

  /** Fallback accessible name: the human language label ("Dansk"), not the raw code. */
  protected readonly languageLabel = computed(() => this.languageOption()?.label ?? this.lang());

  protected onFieldFocus(): void {
    this.hasCaret = true;
  }

  protected onInput(value: string): void {
    this.lastInternalValue = value;
    this.value.set(value);
  }

  /**
   * Splice a token in where the writer last had the caret.
   *
   * The selection survives the blur the opening menu causes, so it still marks
   * the place. Focus is returned to the field afterwards — after the closing
   * menu has handed it back to the trigger, which is why the move waits for the
   * next render rather than happening on the click.
   */
  protected insert(token: string): void {
    const element = this.field()?.nativeElement;
    if (this.disabled() || !element) {
      return;
    }
    const current = this.value();
    const { value: next, caret } = insertToken(
      current,
      token,
      this.hasCaret
        ? {
            start: element.selectionStart ?? current.length,
            end: element.selectionEnd ?? current.length,
          }
        : null,
    );
    this.lastInternalValue = next;
    this.hasCaret = true;
    this.value.set(next);
    this.touch.emit();
    afterNextRender(
      () => {
        element.focus();
        element.setSelectionRange(caret, caret);
      },
      { injector: this.injector },
    );
  }
}
