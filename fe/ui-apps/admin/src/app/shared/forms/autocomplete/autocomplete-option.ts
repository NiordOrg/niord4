import { Directive, ElementRef, inject, input, signal } from '@angular/core';
import type { Highlightable } from '@angular/cdk/a11y';

let optionSeq = 0;

/**
 * A single result row inside {@link Autocomplete}'s listbox panel, and the shared
 * option primitive for any other listbox driven by `ActiveDescendantKeyManager`.
 *
 * Implements CDK's {@link Highlightable} so the shared `ActiveDescendantKeyManager`
 * owns arrow/typeahead navigation — the primitive never hand-rolls keyboard focus.
 * The manager toggles `active` (→ `aria-activedescendant` on the input + the
 * `.ac-option-active` style); a mouse hover uses the same style via `:hover`.
 *
 * Consumers that draw their own rows (not `.ac-option`) read `active()` through the
 * `exportAs` ref and style it themselves, and use `element` to scroll it into view.
 */
@Directive({
  selector: '[appAutocompleteOption]',
  exportAs: 'appAutocompleteOption',
  host: {
    role: 'option',
    '[id]': 'id',
    '[class.ac-option-active]': 'active()',
    '[attr.aria-selected]': 'active() ? "true" : "false"',
  },
})
export class AutocompleteOption<T = unknown> implements Highlightable {
  /** The suggestion this row represents (the value picked on select). */
  readonly value = input.required<T>({ alias: 'appAutocompleteOption' });

  /** Stable id so the combobox input can point `aria-activedescendant` at it. */
  readonly id = `app-ac-option-${optionSeq++}`;

  /** Active (keyboard-highlighted) state, driven by the key manager. */
  readonly active = signal(false);

  /** Rows are never disabled (suggestions are always pickable). */
  readonly disabled = false;

  /** The row's host element — lets a consumer scroll the active option into view. */
  readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

  setActiveStyles(): void {
    this.active.set(true);
  }

  setInactiveStyles(): void {
    this.active.set(false);
  }

  getLabel(): string {
    return this.element.nativeElement.textContent?.trim() ?? '';
  }
}
