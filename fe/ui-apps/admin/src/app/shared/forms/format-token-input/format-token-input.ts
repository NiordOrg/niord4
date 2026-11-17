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
import { FormValueControl } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import { Menu } from '../../components/menu/menu';
import { MenuTriggerFor } from '../../components/menu/menu-trigger.directive';
import type { MenuItem } from '../../models/menu-item.model';
import { insertToken, type FormatToken } from './token-insert';

export type { FormatToken };

/**
 * A format-string field with a token-insert menu, exposed as a Signal Forms
 * `FormValueControl<string>`. Renders a single-line input (or a textarea via `multiline`) plus an
 * "Insert" dropdown listing each token by its friendly label — picking one splices the token's
 * literal text into the field at the caret (replacing any selection) and puts the caret right
 * after it. When the field has never held the caret for the current text (fresh editor open,
 * external reset), the token is appended at the end instead — an untouched field appends rather
 * than inserting at position 0. Every insert refocuses the field and propagates
 * through `value`, so a host's dirty/baseline compare flips. The component is token-agnostic:
 * gating (e.g. offering a token for one main type only) stays with the consumer.
 */
@Component({
  selector: 'app-format-token-input',
  imports: [Icon, Menu, MenuTriggerFor, TranslocoPipe],
  templateUrl: './format-token-input.html',
  host: { class: 'block' },
})
export class FormatTokenInput implements FormValueControl<string> {
  private readonly injector = inject(Injector);

  /** Render a textarea instead of a single-line input. */
  readonly multiline = input(false);
  /** Textarea height (multiline variant only). */
  readonly rows = input(2);
  /** The insertable tokens, offered by label in the insert menu (the consumer owns any gating). */
  readonly tokens = input.required<readonly FormatToken[]>();
  /** Optional id so a parent `<label for>` can target the field. */
  readonly inputId = input<string | undefined>(undefined);
  /** Pre-translated accessible name for the field (the consumer translates). */
  readonly ariaLabel = input<string | undefined>(undefined);

  /** The format string — two-way bound by `[formField]`, or `[value]`/`(valueChange)`. */
  readonly value = model<string>('');
  /** Set by the form when the field is disabled. */
  readonly disabled = input(false);
  /** Emitted on blur (and on insert) for Signal Forms touched-tracking. */
  readonly touch = output<void>();

  private readonly field =
    viewChild.required<ElementRef<HTMLInputElement | HTMLTextAreaElement>>('field');

  /**
   * Whether the field has held the caret for the CURRENT text. A text input reports
   * `selectionStart: 0` even before it was ever focused, so a bare selection read cannot tell
   * "caret at the start" from "never touched" — and an untouched field must APPEND, not insert
   * at position 0.
   */
  private hasCaret = false;
  /** Last value written by this control — used to spot external writes (editor open / reset). */
  private lastInternalValue: string | null = null;

  protected readonly tokenItems = computed<readonly MenuItem[]>(() =>
    this.tokens().map(({ token, label }) => ({
      label,
      command: () => this.insert(token),
    })),
  );

  constructor() {
    // An external value write means the field's caret no longer belongs to the text — fall back
    // to appending until the user focuses the field again.
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

  protected onFieldFocus(): void {
    this.hasCaret = true;
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    this.lastInternalValue = value;
    this.value.set(value);
  }

  protected insert(token: string): void {
    if (this.disabled()) {
      return;
    }
    const field = this.field().nativeElement;
    const current = this.value();
    // The selection survives the field's blur (clicking the menu doesn't clear it), so it still
    // marks where the user last had the caret. Clamped in case of a stale read.
    const { value: next, caret } = insertToken(
      current,
      token,
      this.hasCaret
        ? {
            start: field.selectionStart ?? current.length,
            end: field.selectionEnd ?? current.length,
          }
        : null,
    );
    this.lastInternalValue = next;
    this.hasCaret = true;
    this.value.set(next);
    this.touch.emit();
    // Refocus once the new text is rendered — this also runs after the closing CDK menu has
    // returned focus to the trigger, so the field wins — and park the caret after the token.
    afterNextRender(
      () => {
        field.focus();
        field.setSelectionRange(caret, caret);
      },
      { injector: this.injector },
    );
  }
}
