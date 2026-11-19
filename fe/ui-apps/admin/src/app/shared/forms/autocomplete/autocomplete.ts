import { NgTemplateOutlet } from '@angular/common';
import { ActiveDescendantKeyManager } from '@angular/cdk/a11y';
import {
  CdkConnectedOverlay,
  CdkOverlayOrigin,
  type ConnectedPosition,
  Overlay,
  type ScrollStrategy,
} from '@angular/cdk/overlay';
import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  TemplateRef,
  computed,
  contentChild,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';

import { Icon } from '../../icons/icon';
import { AutocompleteOption } from './autocomplete-option';

/** Below-first, flip-above fallback — mirrors the anchored-overlay rule (ui-standards §2). */
const POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

let listboxSeq = 0;

/**
 * Headless multi-select chip combobox on the CDK overlay. It renders selected
 * items as `.chip`s + a text input and portals a `role="listbox"` results panel
 * via `@angular/cdk/overlay` (fixed, viewport-bounded, mirroring `PickerPopover`).
 * Keyboard nav/activedescendant is owned by CDK's `ActiveDescendantKeyManager`.
 *
 * It holds NO async/search state: the consumer (typically
 * `EntityMultiselectField`) debounces `(query)`, fetches, and feeds `suggestions`
 * / `value` / `fetchError` back in. Selection semantics stay in the consumer —
 * this emits the *new value array* via `(select)` and lets the CVA own it, so a
 * modal `appendOne` pick and a search pick coexist (no writeControlValue drop).
 *
 * Single-select consumers pass `[multiple]="false"` (at most one chip). Free-text
 * consumers pass `[allowFreeText]="true"` + `[text]` (the input mirrors the value;
 * no chip). Custom rows: project `<ng-template #option let-item>`.
 */
@Component({
  selector: 'app-autocomplete',
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, NgTemplateOutlet, AutocompleteOption, Icon],
  templateUrl: './autocomplete.html',
  host: { class: 'app-autocomplete' },
})
export class Autocomplete<T> {
  private readonly overlay = inject(Overlay);
  private readonly injector = inject(Injector);

  // --- Value / options ---
  /** Currently selected items, rendered as chips. */
  readonly value = input<readonly T[]>([]);
  /** Suggestions to show in the panel (owned + fetched by the consumer). */
  readonly suggestions = input<readonly T[]>([]);
  /** Label accessor for chips + default row rendering. */
  readonly itemLabel = input<(item: T) => string>((item) => String(item));

  // --- Behaviour ---
  readonly multiple = input(true);
  readonly disabled = input(false);
  readonly showClear = input(true);
  /**
   * Opt-in: focusing the field asks for the empty-query list and opens the panel, so the user can
   * browse the whole set instead of having to type a name first. Off by default — for most
   * consumers the set is far too large to dump on focus.
   */
  readonly browseOnFocus = input(false);
  /**
   * Glyph for the clear button. The default reads as "clear this box" and suits a free-text
   * search. A control whose clear DISCARDS A SELECTION passes `lucideTrash2` instead — both to
   * say so, and because inside a message filter panel the default is the same circle-x the panel
   * uses for "remove this filter", and two identical buttons sitting side by side is a trap.
   */
  readonly clearIcon = input('lucideCircleX');
  /**
   * Optional glyph drawn at the head of the control — a Lucide icon id. It says what the box
   * searches (`lucideSearch`) on a field whose label alone leaves that ambiguous. Decorative:
   * the accessible name still comes from `ariaLabel` / the associated `<label>`.
   */
  readonly leadingIcon = input<string | undefined>(undefined);
  /** Minimum typed length before the empty/error message is shown. */
  readonly minLength = input(1);
  /** When true, the input text is consumer-controlled and no chip is drawn. */
  readonly allowFreeText = input(false);
  /**
   * How wide the results panel is allowed to be.
   *
   * `'control'` (the default) pins it to the box's pixel width, which is right
   * for rows that are a single name: the panel reads as part of the field.
   * `'content'` makes that width a FLOOR instead and lets the panel grow to its
   * widest row, capped by the panel class — for rows that carry qualifiers beside
   * the name, where pinning the width truncates the very thing the reader is
   * choosing by and leaves them widening a filter panel to read a list.
   */
  readonly panelWidth = input<'control' | 'content'>('control');

  // --- Presentation (already-resolved strings; the primitive is Transloco-free) ---
  readonly placeholder = input('');
  readonly ariaLabel = input('');
  readonly loading = input(false);
  readonly fetchError = input(false);
  readonly emptyMessage = input('');
  readonly errorMessage = input('');
  readonly clearLabel = input('');
  readonly removeLabel = input('');
  readonly inputId = input('');
  /** Seeds/overrides the input text (free-text + programmatic value reflection). */
  readonly text = input('');

  // --- Outputs ---
  /** Raw text typed by the user — the consumer debounces + searches. */
  readonly query = output<string>();
  /** The new value array after an add / remove / single-select replace. */
  readonly selectionChange = output<readonly T[]>();
  readonly clear = output<void>();
  readonly touched = output<void>();

  /** Optional custom row template: `<ng-template #option let-item>`. */
  readonly optionTpl = contentChild<TemplateRef<{ $implicit: T }>>('option');
  /**
   * Row template bound as an input — for wrappers whose own consumers can't
   * project content into this primitive (e.g. `EntityMultiselectControl`
   * forwarding a leaf field's row template). A projected `#option` wins.
   */
  readonly optionTemplate = input<TemplateRef<{ $implicit: T }> | undefined>(undefined);

  /** The effective row template: projected `#option`, else the `optionTemplate` input. */
  protected readonly rowTpl = computed(() => this.optionTpl() ?? this.optionTemplate());

  private readonly control = viewChild.required<ElementRef<HTMLElement>>('control');
  private readonly inputEl = viewChild.required<ElementRef<HTMLInputElement>>('input');
  private readonly optionItems = viewChildren(AutocompleteOption);

  protected readonly positions = POSITIONS;
  protected readonly scrollStrategy: ScrollStrategy = this.overlay.scrollStrategies.reposition();
  private readonly instance = listboxSeq++;
  protected readonly listboxId = `app-ac-listbox-${this.instance}`;

  /** Set while the input takes the focus back from a removed button: see `focusInputQuietly`. */
  private refocusing = false;

  /**
   * The input's text — resets to `text()` whenever the consumer changes it. A pick in free-text
   * single-select mode sets it to `text()` rather than emptying it (see `pick`).
   */
  protected readonly queryText = linkedSignal(() => this.text());
  protected readonly panelOpen = signal(false);
  protected readonly controlWidth = signal(0);
  protected readonly activeId = signal<string | undefined>(undefined);

  private readonly keyManager: ActiveDescendantKeyManager<AutocompleteOption<unknown>>;

  constructor() {
    this.keyManager = new ActiveDescendantKeyManager(this.optionItems, this.injector).withWrap();
    this.keyManager.change.subscribe(() => this.activeId.set(this.keyManager.activeItem?.id));
    inject(DestroyRef).onDestroy(() => this.keyManager.destroy());
  }

  protected readonly showChips = computed(() => this.value().length > 0 && !this.allowFreeText());

  protected readonly placeholderText = computed(() => (this.showChips() ? '' : this.placeholder()));

  private readonly hasQuery = computed(
    () => this.queryText().trim().length >= Math.max(1, this.minLength()),
  );

  protected readonly showPanel = computed(
    () =>
      this.panelOpen() &&
      !this.disabled() &&
      (this.browseOnFocus() || this.loading() || this.suggestions().length > 0 || this.hasQuery()),
  );

  protected readonly emptyText = computed(() =>
    this.fetchError() ? this.errorMessage() : this.emptyMessage(),
  );

  /** True where the panel is content-sized rather than pinned to the control. */
  private readonly contentSized = computed(() => this.panelWidth() === 'content');

  /**
   * The overlay's fixed width — the control's, or none at all when the panel is
   * content-sized. `''` is how the CDK directive is told there is no fixed width;
   * a `0` would be read as a width of zero.
   */
  protected readonly overlayWidth = computed<number | string>(() =>
    this.contentSized() ? '' : this.controlWidth(),
  );

  /** The floor a content-sized panel may not fall below: the control's own width. */
  protected readonly overlayMinWidth = computed<number | string>(() =>
    this.contentSized() ? this.controlWidth() : '',
  );

  /**
   * Flexible dimensions only where the panel is content-sized: it is what keeps a
   * panel that has outgrown its box inside the viewport instead of running off the
   * right edge (this control never pushes, and the fallback position only flips
   * vertically).
   */
  protected readonly flexibleDimensions = computed(() => this.contentSized());

  /** The panel-class hook, plus the width cap a content-sized panel is held to. */
  protected readonly panelClasses = computed<string[]>(() =>
    this.contentSized() ? ['ac-overlay', 'ac-overlay-content'] : ['ac-overlay'],
  );

  protected onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.queryText.set(value);
    this.keyManager.setActiveItem(-1);
    this.activeId.set(undefined);
    this.query.emit(value);
    this.open();
  }

  protected onFocus(): void {
    if (this.refocusing) return;
    if (this.browseOnFocus()) {
      // Ask the consumer for the empty-query list and open regardless — on a cold field there are
      // no suggestions yet, so waiting for them would mean the panel never opens on first focus.
      this.query.emit(this.queryText());
      this.open();
      return;
    }
    if (this.suggestions().length > 0 || this.hasQuery()) this.open();
  }

  protected onBlur(): void {
    this.touched.emit();
    this.close();
  }

  protected onKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
        if (!this.panelOpen()) this.open();
        this.keyManager.setNextItemActive();
        event.preventDefault();
        break;
      case 'ArrowUp':
        if (!this.panelOpen()) this.open();
        this.keyManager.setPreviousItemActive();
        event.preventDefault();
        break;
      case 'Enter': {
        const active = this.keyManager.activeItem;
        if (this.showPanel() && active) {
          this.pick(active.value() as T);
          event.preventDefault();
        }
        break;
      }
      case 'Escape':
        if (this.panelOpen()) {
          this.close();
          event.preventDefault();
          event.stopPropagation();
        }
        break;
      case 'Backspace':
        if (this.multiple() && this.queryText() === '' && this.value().length > 0) {
          this.removeChip(this.value()[this.value().length - 1]);
        }
        break;
    }
  }

  // Mouse selection runs on mousedown + preventDefault so the input never blurs
  // (which would close the panel before the click resolves).
  protected onOptionMousedown(event: MouseEvent, item: T): void {
    event.preventDefault();
    this.pick(item);
  }

  // The chip-remove and clear buttons act on click, which is also what Enter and Space on a
  // focused button fire. Their mousedown only keeps the focus where it is (the input, while the
  // user types), as the rows do; acting on mousedown would leave the keyboard with no way to
  // press them.
  protected keepInputFocus(event: MouseEvent): void {
    event.preventDefault();
  }

  protected onRemoveClick(event: MouseEvent, item: T): void {
    const hadFocus = this.holdsFocus(event);
    this.removeChip(item);
    if (hadFocus) this.focusInputQuietly();
  }

  protected onClearClick(event: MouseEvent): void {
    const hadFocus = this.holdsFocus(event);
    this.clearAll();
    if (hadFocus) this.focusInputQuietly();
  }

  /** The id of a chip's label, and of its remove button, which is named by both. */
  protected chipLabelId(index: number): string {
    return `app-ac-${this.instance}-chip-${index}`;
  }

  protected chipRemoveId(index: number): string {
    return `app-ac-${this.instance}-chip-${index}-remove`;
  }

  /**
   * Whether the pressed button holds the focus: always from the keyboard, never from the mouse,
   * whose press `keepInputFocus` kept off it. Such a button is about to leave the DOM (its chip,
   * or the whole value, is gone), which would drop the focus on the page's body.
   */
  private holdsFocus(event: MouseEvent): boolean {
    const button = event.currentTarget;
    return button instanceof HTMLElement && button.ownerDocument.activeElement === button;
  }

  /**
   * Moves the focus to the input without the side effects of a user focusing it: no browse query
   * and no panel opened over the box the user has just emptied.
   */
  private focusInputQuietly(): void {
    this.refocusing = true;
    try {
      this.inputEl().nativeElement.focus();
    } finally {
      this.refocusing = false;
    }
  }

  protected onOutsideClick(event: MouseEvent): void {
    const control = this.control().nativeElement;
    if (event.target instanceof Node && control.contains(event.target)) return;
    this.close();
  }

  private pick(item: T): void {
    let next: readonly T[];
    if (this.multiple()) {
      const label = this.itemLabel()(item);
      next = this.value().some((existing) => this.itemLabel()(existing) === label)
        ? this.value()
        : [...this.value(), item];
    } else {
      next = [item];
    }
    // A chip shows the pick, so the box empties for the next search. A free-text single select
    // keeps the consumer's text: when the pick changes it, the linked signal resets to the new
    // text; when the picked label equals what was typed it does not change, and an empty box
    // here would never be refilled.
    this.queryText.set(this.allowFreeText() && !this.multiple() ? this.text() : '');
    this.selectionChange.emit(next);
    this.touched.emit();
    this.close();
  }

  // Chips carry the exact `value()` element, so identity removal is precise
  // (safe even when two entities share a display label).
  private removeChip(item: T): void {
    this.selectionChange.emit(this.value().filter((existing) => existing !== item));
    this.touched.emit();
  }

  private clearAll(): void {
    this.queryText.set('');
    this.clear.emit();
    this.touched.emit();
    this.close();
  }

  protected open(): void {
    if (this.disabled()) return;
    this.controlWidth.set(this.control().nativeElement.offsetWidth);
    this.panelOpen.set(true);
  }

  protected close(): void {
    this.panelOpen.set(false);
    this.keyManager.setActiveItem(-1);
    this.activeId.set(undefined);
  }
}
