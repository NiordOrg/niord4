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
  afterNextRender,
  computed,
  contentChild,
  inject,
  input,
  model,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { FormValueControl } from '@angular/forms/signals';

import { Icon } from '../../icons/icon';
import { SelectOption } from './select-option';

/** Below-first, flip-above fallback — mirrors the anchored-overlay rule (ui-standards §2). */
const POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
];

let listboxSeq = 0;

/**
 * Headless single-select dropdown built on CDK a11y (`ActiveDescendantKeyManager`)
 * + CDK overlay. A `role="combobox"` trigger button
 * shows the selected label (or placeholder) and portals a `role="listbox"` panel
 * via `@angular/cdk/overlay` (fixed, viewport-bounded, width tracks the trigger —
 * mirroring {@link Autocomplete} / `PickerPopover`). Arrow / typeahead /
 * active-descendant navigation is owned by CDK's `ActiveDescendantKeyManager`;
 * each row's `aria-selected` reflects the committed value.
 *
 * It is a Signal Forms `FormValueControl`: bind it with `[formField]` inside a
 * form, or standalone via `[value]`/`(valueChange)` — the `value` model backs
 * both. `optionValue` (a field name) makes the control value a scalar; without
 * it the whole option object is the value. `(valueChange)` fires whenever the
 * value changes (user commit or a programmatic write).
 *
 * Without `optionLabel`, object options are matched to the value by identity; with it (or for
 * primitive options) also by label.
 *
 * Action mode: `[clearOnPick]="true"` turns the control into an action trigger. Every pick
 * emits `(picked)` with the option's value, also a second pick of the same option, and the
 * control keeps no value: the trigger shows the placeholder and no row is selected.
 *
 * Rich rows: project `<ng-template #option let-opt>`. A distinct trigger render
 * can be projected as `<ng-template #selectedOption let-opt>` (falls back to
 * `#option`, then the label string).
 */
@Component({
  selector: 'app-select',
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, NgTemplateOutlet, SelectOption, Icon],
  templateUrl: './select.html',
  host: { class: 'app-select' },
})
export class Select<T = unknown> implements FormValueControl<unknown> {
  private readonly overlay = inject(Overlay);
  private readonly injector = inject(Injector);

  // --- Options / value accessors ---
  readonly options = input<readonly T[]>([]);
  /** Label field name, or an accessor. Absent → the option renders via `String(option)`. */
  readonly optionLabel = input<string | ((option: T) => string) | undefined>(undefined);
  /** Value field name. Set → the control value is `option[optionValue]` (a scalar). */
  readonly optionValue = input<string | undefined>(undefined);
  /** Disabled field name — matching options are skipped by keyboard nav + click. */
  readonly optionDisabled = input<string | undefined>(undefined);

  /**
   * The control value — two-way bound by `[formField]`, or read/written standalone
   * via `[value]`/`(valueChange)`. `undefined` = no selection. The type depends on
   * `optionValue` (scalar) vs whole-option, which a template can't infer, so it is
   * `unknown`; consumers narrow it at the binding via `$any(...)`.
   */
  readonly value = model<unknown>(undefined);

  // --- Behaviour / presentation (already-resolved strings; the primitive is Transloco-free) ---
  readonly placeholder = input('');
  readonly ariaLabel = input('');
  /** Marks the combobox as one that must be given a value. */
  readonly ariaRequired = input(false);
  /** Set by the form when the field is disabled, or bound directly outside a form. */
  readonly disabled = input(false);
  readonly showClear = input(false);
  readonly loading = input(false);
  readonly inputId = input('');
  readonly clearLabel = input('');
  /** Panel max-height CSS (e.g. `'min(20rem, 60vh)'`); `'auto'` = uncapped. */
  readonly scrollHeight = input<string | undefined>(undefined);
  /**
   * Treat `''` as "nothing chosen" rather than as a value.
   *
   * Off by default, because several lists carry a deliberate empty-string option with a label
   * of its own ("Any language", "None") and that IS a selection. Turn it on where `''` is the
   * unanswered state — a field seeded blank and required — so the trigger shows the
   * placeholder instead of an empty line.
   */
  readonly emptyIsNoSelection = input(false);
  /**
   * Action mode: a pick emits `(picked)` and is not kept as the value, so the same option can
   * be picked again. For a control that starts an action ("Change all statuses"), not one that
   * holds a choice; bind `(picked)` and leave `[value]` unbound.
   */
  readonly clearOnPick = input(false);

  /** Emitted on blur so Signal Forms touched-tracking + `debounce('blur')` work. */
  readonly touch = output<void>();
  /** Action mode only: the picked option's value, on every pick. */
  readonly picked = output<unknown>();

  /** Custom row template: `<ng-template #option let-opt>`. */
  readonly optionTpl = contentChild<TemplateRef<{ $implicit: T }>>('option');
  /** Custom trigger template: `<ng-template #selectedOption let-opt>`. */
  readonly selectedTpl = contentChild<TemplateRef<{ $implicit: T }>>('selectedOption');

  private readonly control = viewChild.required<ElementRef<HTMLElement>>('control');
  private readonly optionItems = viewChildren(SelectOption);

  protected readonly positions = POSITIONS;
  protected readonly scrollStrategy: ScrollStrategy = this.overlay.scrollStrategies.reposition();
  protected readonly listboxId = `app-select-listbox-${listboxSeq++}`;

  protected readonly panelOpen = signal(false);
  protected readonly controlWidth = signal(0);
  protected readonly activeId = signal<string | undefined>(undefined);

  private readonly keyManager: ActiveDescendantKeyManager<SelectOption<unknown>>;

  constructor() {
    this.keyManager = new ActiveDescendantKeyManager(this.optionItems, this.injector)
      .withWrap()
      .withTypeAhead();
    this.keyManager.change.subscribe(() => this.activeId.set(this.keyManager.activeItem?.id));
    inject(DestroyRef).onDestroy(() => this.keyManager.destroy());
  }

  /**
   * The selected value (`null`/`undefined` — and `''` under `emptyIsNoSelection` — render as
   * none; in action mode nothing is ever selected).
   */
  private readonly selectedValue = computed<unknown>(() => {
    if (this.clearOnPick()) return undefined;
    const value = this.value();
    return value == null || (this.emptyIsNoSelection() && value === '') ? undefined : value;
  });

  private labelOf(option: T): string {
    const accessor = this.optionLabel();
    if (typeof accessor === 'function') return accessor(option);
    if (typeof accessor === 'string')
      return String((option as Record<string, unknown>)?.[accessor] ?? '');
    return option == null ? '' : String(option);
  }

  /** Template-facing label accessor (default row + trigger rendering). */
  protected optionLabelFor(option: T): string {
    return this.labelOf(option);
  }

  private valueOf(option: T): unknown {
    const field = this.optionValue();
    return field ? (option as Record<string, unknown>)?.[field] : option;
  }

  protected isOptionDisabled(option: T): boolean {
    const field = this.optionDisabled();
    return field ? Boolean((option as Record<string, unknown>)?.[field]) : false;
  }

  /**
   * Whether an option matches the current value (drives `aria-selected` + highlight). An object
   * option without `optionLabel` is matched by identity only: its label would be
   * `String(option)`, "[object Object]" for every one, so a label match would select them all.
   */
  protected isSelectedOption(option: T): boolean {
    const value = this.selectedValue();
    if (value == null) return false;
    if (this.optionValue()) return this.valueOf(option) === value;
    if (option === value) return true;
    const labelled =
      this.optionLabel() !== undefined || option === null || typeof option !== 'object';
    return labelled && this.labelOf(option) === this.labelOf(value as T);
  }

  /** The option matching the current value; for a whole-object value not in the list, the value. */
  protected readonly selectedOption = computed<T | undefined>(() => {
    const value = this.selectedValue();
    if (value == null) return undefined;
    const found = this.options().find((option) => this.isSelectedOption(option));
    if (found !== undefined) return found;
    return this.optionValue() ? undefined : (value as T);
  });

  protected readonly hasSelection = computed(() => this.selectedOption() !== undefined);
  protected readonly triggerLabel = computed(() => {
    const option = this.selectedOption();
    return option === undefined ? '' : this.labelOf(option);
  });
  protected readonly triggerTpl = computed(() => this.selectedTpl() ?? this.optionTpl());

  protected readonly panelStyle = computed<Record<string, string>>(() => {
    const height = this.scrollHeight();
    if (!height || height === 'auto') return {};
    return { 'max-height': height } as Record<string, string>;
  });

  // --- Interaction ---

  protected onTriggerClick(): void {
    if (this.disabled() || this.loading()) return;
    if (this.panelOpen()) this.close();
    else this.open();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.disabled() || this.loading()) return;
    switch (event.key) {
      case 'ArrowDown':
        if (this.panelOpen()) this.keyManager.setNextItemActive();
        else this.open();
        event.preventDefault();
        break;
      case 'ArrowUp':
        if (this.panelOpen()) this.keyManager.setPreviousItemActive();
        else this.open();
        event.preventDefault();
        break;
      case 'Home':
        if (this.panelOpen()) {
          this.keyManager.setFirstItemActive();
          event.preventDefault();
        }
        break;
      case 'End':
        if (this.panelOpen()) {
          this.keyManager.setLastItemActive();
          event.preventDefault();
        }
        break;
      case 'Enter':
      case ' ':
        if (this.panelOpen()) this.commitActive();
        else this.open();
        event.preventDefault();
        break;
      case 'Escape':
        if (this.panelOpen()) {
          this.close();
          event.preventDefault();
          event.stopPropagation();
        }
        break;
      case 'Tab':
        this.close();
        break;
      default:
        // Delegate printable keys to the manager's typeahead (opens on first key).
        if (event.key.length === 1) {
          if (!this.panelOpen()) this.open();
          this.keyManager.onKeydown(event);
        }
        break;
    }
  }

  protected onBlur(): void {
    this.touch.emit();
    this.close();
  }

  // Mouse selection runs on mousedown + preventDefault so the trigger never blurs
  // (which would close the panel before the click resolves).
  protected onOptionMousedown(event: MouseEvent, option: T): void {
    event.preventDefault();
    if (this.isOptionDisabled(option)) return;
    this.selectOption(option);
  }

  protected onClearMousedown(event: MouseEvent): void {
    event.preventDefault();
    this.commitValue(null);
    this.close();
  }

  protected onOutsideClick(event: MouseEvent): void {
    const control = this.control().nativeElement;
    if (event.target instanceof Node && control.contains(event.target)) return;
    this.close();
  }

  private commitActive(): void {
    const active = this.keyManager.activeItem;
    if (active) this.selectOption(active.value() as T);
  }

  private selectOption(option: T): void {
    if (this.clearOnPick()) {
      // An action, not a choice: emit it every time and keep no value to compare the next pick to.
      this.picked.emit(this.valueOf(option));
      this.touch.emit();
    } else {
      this.commitValue(this.valueOf(option));
    }
    this.close();
  }

  private commitValue(value: unknown): void {
    // Setting the `value` model emits `valueChange`; blur-touch is separate.
    this.value.set(value);
    this.touch.emit();
  }

  protected open(): void {
    if (this.disabled() || this.loading()) return;
    this.controlWidth.set(this.control().nativeElement.offsetWidth);
    this.panelOpen.set(true);
    // Pre-highlight the committed row (or the first) once the panel has rendered.
    afterNextRender(
      () => {
        const index = this.options().findIndex((option) => this.isSelectedOption(option));
        if (index >= 0) this.keyManager.setActiveItem(index);
        else this.keyManager.setFirstItemActive();
      },
      { injector: this.injector },
    );
  }

  protected close(): void {
    if (!this.panelOpen()) return;
    this.panelOpen.set(false);
    this.keyManager.setActiveItem(-1);
    this.activeId.set(undefined);
  }
}
