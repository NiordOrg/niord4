import { Directive, ElementRef, inject, input, signal } from '@angular/core';
import type { Highlightable } from '@angular/cdk/a11y';

let optionSeq = 0;

/**
 * A single option row inside {@link Select}'s listbox panel.
 *
 * Implements CDK's {@link Highlightable} so the shared `ActiveDescendantKeyManager`
 * owns arrow/typeahead navigation — the primitive never hand-rolls keyboard focus.
 * The manager toggles `active` (→ `aria-activedescendant` on the trigger + the
 * `.sel-option-active` style); `aria-selected` reflects the *committed* selection
 * (a listbox distinction the autocomplete's chip model doesn't need), and a mouse
 * hover uses the active style via `:hover`.
 */
@Directive({
  selector: '[appSelectOption]',
  host: {
    role: 'option',
    '[id]': 'id',
    '[class.sel-option-active]': 'active()',
    '[class.sel-option-selected]': 'selected()',
    '[attr.aria-selected]': 'selected() ? "true" : "false"',
    '[attr.aria-disabled]': 'disabled ? "true" : null',
  },
})
export class SelectOption<T = unknown> implements Highlightable {
  /** The option this row represents (the source of the picked value). */
  readonly value = input.required<T>({ alias: 'appSelectOption' });

  /** Whether this row is the committed selection (drives `aria-selected`). */
  readonly selected = input(false);

  /** Whether this row is non-pickable (skipped by the key manager). */
  readonly disabledOption = input(false);

  /** Stable id so the combobox trigger can point `aria-activedescendant` at it. */
  readonly id = `app-sel-option-${optionSeq++}`;

  /** Active (keyboard-highlighted) state, driven by the key manager. */
  readonly active = signal(false);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Read as a plain property by the key manager's skip predicate. */
  get disabled(): boolean {
    return this.disabledOption();
  }

  setActiveStyles(): void {
    this.active.set(true);
  }

  setInactiveStyles(): void {
    this.active.set(false);
  }

  getLabel(): string {
    return this.host.nativeElement.textContent?.trim() ?? '';
  }
}
