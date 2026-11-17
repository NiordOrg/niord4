import { Component, ElementRef, computed, input, output, viewChild } from '@angular/core';
import type { FocusOrigin } from '@angular/cdk/a11y';

import { Icon } from '../../icons/icon';
import { MenuTriggerFor } from '../menu/menu-trigger.directive';
import type { Menu } from '../menu/menu';

/**
 * Standard "click to open a popup menu" trigger for content areas.
 *
 * Renders a quiet button with a leading `<app-icon>`, an optional label, an
 * optional count badge, and a trailing chevron so it reads as a dropdown
 * trigger. It hosts `appMenuTriggerFor` on its inner `<button>` and points it at
 * the `[menu]` (`<app-menu>`) instance — CDK owns the open/close/keyboard/focus
 * mechanics, so the component is trigger-only and re-surfaces `(opened)` /
 * `(closed)` for the few call-sites that react to open (e.g. lazy reloads).
 *
 * Designed for light-context surfaces (data tables, content panels). The
 * dark-context header buttons stay inline because their styling and per-item
 * icon decoration differ enough that pushing them through this component would
 * dilute the abstraction.
 *
 * ```html
 * <app-menu-trigger icon="lucideSettings" [ariaLabel]="… | transloco" [menu]="actionMenu" />
 * <app-menu #actionMenu [model]="items()" />
 * ```
 */
@Component({
  selector: 'app-menu-trigger',
  imports: [Icon, MenuTriggerFor],
  template: `
    <button
      #btn
      type="button"
      [class]="buttonClass()"
      [appMenuTriggerFor]="menu()"
      [align]="align()"
      (opened)="opened.emit()"
      (closed)="closed.emit()"
      (click)="$event.stopPropagation()"
      [attr.aria-label]="effectiveAriaLabel()"
    >
      <app-icon [name]="icon()" [class]="iconClass()" />
      @if (label(); as l) {
        <span class="inline-flex items-center">
          {{ l }}
          @if (badge(); as count) {
            <span
              class="ml-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 text-[0.625rem] leading-none font-semibold text-fg-on-primary"
              >{{ count }}</span
            >
          }
          @if (showChevron()) {
            <app-icon name="lucideChevronDown" class="ml-0.5 text-xs opacity-70" />
          }
        </span>
      } @else if (showChevron()) {
        <app-icon name="lucideChevronDown" class="text-xs opacity-70" />
      }
    </button>
  `,
})
export class MenuTrigger {
  /** Leading glyph — a Lucide icon id (e.g. `'lucideSettings'`). */
  readonly icon = input.required<string>();
  readonly label = input<string | undefined>(undefined);
  /**
   * Button flavor for LABELED triggers (icon-only triggers always render as
   * `icon-button`): `'primary'` when the menu IS the surface's primary action
   * (an "Add …" split menu), the tertiary default everywhere else.
   */
  readonly variant = input<'tertiary' | 'primary'>('tertiary');
  /** Extra class string merged onto the leading icon (e.g. a size override). */
  readonly iconClass = input<string>('');
  readonly size = input<'small' | 'large' | undefined>('small');
  /**
   * Optional count badge shown after the label (e.g. number of active filters).
   * Rendered only when truthy (> 0); only supported on triggers that have a label.
   */
  readonly badge = input<number | undefined>(undefined);
  /** Whether to render the trailing chevron indicator. */
  readonly showChevron = input<boolean>(true);
  /**
   * Explicit accessible name. Defaults to the visible `label` when present.
   * Required for icon-only triggers where the label is omitted.
   */
  readonly ariaLabel = input<string | undefined>(undefined);
  /** The `<app-menu>` this trigger opens. */
  readonly menu = input.required<Menu>();
  /** `'end'` right-aligns the opened menu to the trigger (for right-docked triggers). */
  readonly align = input<'start' | 'end'>('start');

  readonly opened = output<void>();
  readonly closed = output<void>();

  private readonly button = viewChild.required<ElementRef<HTMLButtonElement>>('btn');
  private readonly triggerFor = viewChild.required(MenuTriggerFor);

  /**
   * The rendered trigger button element — the anchor for a downstream picker
   * popover (the menu row that opened it is gone once the menu closes).
   */
  get triggerElement(): HTMLElement {
    return this.button().nativeElement;
  }

  /**
   * Re-open the menu — used to keep a menu open after a "sticky" item (e.g. a
   * filter toggle) whose action shouldn't dismiss the menu. Scheduled after the
   * CDK item-trigger's own close so the panel is re-created in the same frame.
   */
  reopen(): void {
    this.triggerFor().open();
  }

  /**
   * Opens the menu as a use of the button does — focus on the button, then on the menu's first item,
   * or its last for the Up arrow (see `MenuTriggerFor.openWithFocus`). For a lightweight stand-in
   * that took the button's place until the user reached for it.
   */
  openFromButton(item: 'first' | 'last', origin: FocusOrigin): void {
    this.button().nativeElement.focus();
    this.triggerFor().openWithFocus(item, origin);
  }

  protected readonly effectiveAriaLabel = computed(
    () => this.ariaLabel() ?? this.label() ?? undefined,
  );
  protected readonly buttonClass = computed(() => {
    const base = this.label() ? `btn-${this.variant()}` : 'icon-button';
    return this.size() === 'small' ? `${base} h-8 px-2` : base;
  });
}
