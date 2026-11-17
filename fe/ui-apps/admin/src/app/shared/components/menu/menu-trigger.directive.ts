import { DestroyRef, Directive, effect, inject, input } from '@angular/core';
import type { FocusOrigin } from '@angular/cdk/a11y';
import { CdkMenuTrigger, MENU_STACK } from '@angular/cdk/menu';
import type { ConnectedPosition } from '@angular/cdk/overlay';

import type { Menu } from './menu';

/**
 * Marks a field OUTSIDE the menu that an open menu may hand the caret to — a menu item that
 * enables a text filter, say, focusing the field it just revealed. Focus landing there does
 * NOT dismiss the menu (so a multi-select menu stays available); the first keystroke in the
 * field does, because by then the user has committed to typing rather than picking.
 */
export const MENU_CARET_HANDOFF_ATTR = 'data-menu-caret-handoff';

/**
 * Right-aligned dropdown positions (the menu's right edge tracks the trigger's
 * right edge). For triggers docked to the right of a toolbar whose width can
 * change — e.g. a count badge appearing — a `start`-aligned menu shifts sideways
 * as the trigger's left edge moves; `end` alignment keeps it put.
 */
const END_ALIGNED_POSITIONS: ConnectedPosition[] = [
  { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top' },
  { originX: 'end', originY: 'top', overlayX: 'end', overlayY: 'bottom' },
];

/**
 * Turns any element into the opener for an `<app-menu>` — the niord seam over
 * CDK's `CdkMenuTrigger`. Put it on a raw `<button>` (header nav triggers,
 * editor/geometry cog) or let the styled `<app-menu-trigger>` host it:
 *
 * ```html
 * <button [appMenuTriggerFor]="cogMenu" (opened)="…"><app-icon name="lucideSettings" /></button>
 * <app-menu #cogMenu [model]="items()" />
 * ```
 *
 * It wires the `<app-menu>`'s panel template into the underlying
 * `CdkMenuTrigger` (which opens instantly on click, closes on outside-click /
 * Escape / item-trigger, and manages focus return), re-exposes
 * `open()/close()/toggle()/isOpen()` for the few imperative call-sites, and
 * surfaces `(opened)`/`(closed)`. `aria-haspopup="menu"` and `aria-expanded`
 * are set automatically by `CdkMenuTrigger`, so no manual `[attr.aria-*]`.
 *
 * It also honours {@link MENU_CARET_HANDOFF_ATTR}: focus moving to a field marked with that
 * attribute keeps the menu up instead of dismissing it, until the user types there.
 */
@Directive({
  selector: '[appMenuTriggerFor]',
  exportAs: 'appMenuTrigger',
  host: { '(document:focusin)': 'onDocumentFocusIn($event)' },
  hostDirectives: [
    {
      directive: CdkMenuTrigger,
      outputs: ['cdkMenuOpened: opened', 'cdkMenuClosed: closed'],
    },
  ],
})
export class MenuTriggerFor {
  private readonly cdk = inject(CdkMenuTrigger);
  private readonly menuStack = inject(MENU_STACK);
  /** The hand-off field we've armed, so a re-focus doesn't stack duplicate listeners. */
  private armedHandoff: Element | undefined;

  /** The `<app-menu>` instance this trigger opens. */
  readonly menu = input.required<Menu>({ alias: 'appMenuTriggerFor' });
  /** `'end'` right-aligns the menu to the trigger (stable under trigger width changes). */
  readonly align = input<'start' | 'end'>('start');

  constructor() {
    // Sync the menu's panel template into the CDK trigger. `open()` reads
    // `menuTemplateRef` live, so the imperative set (rather than the aliased
    // `cdkMenuTriggerFor` input) is enough; the effect re-runs when the
    // `<app-menu>`'s `viewChild` template resolves. The panel's `<div cdkMenu>`
    // resolves its own MENU_TRIGGER/MENU_STACK from the per-trigger portal
    // injector forwarded by `<app-menu>`'s `appMenuOutletInjector`.
    effect(() => {
      const menu = this.menu();
      this.cdk.menuTemplateRef = menu.template() ?? null;
    });

    // Right-align the panel when asked, so a trigger that grows (e.g. a filter
    // count badge) doesn't drag the open menu sideways with its left edge.
    // `undefined` falls back to CDK's default below-start positions at open time
    // (`_getOverlayPositions` uses `menuPosition ?? …`), so the cast is safe.
    effect(() => {
      this.cdk.menuPosition = (
        this.align() === 'end' ? END_ALIGNED_POSITIONS : undefined
      ) as ConnectedPosition[];
    });

    inject(DestroyRef).onDestroy(() =>
      this.armedHandoff?.removeEventListener('input', this.closeOnHandoffInput),
    );
  }

  /**
   * Keeps the menu open when focus lands on a {@link MENU_CARET_HANDOFF_ATTR} field, and closes
   * it on the first keystroke there.
   *
   * CDK dismisses a menu whose stack loses focus: the `focusout` pushes `false` into
   * `MenuStack.hasFocus`, which is `debounceTime(0)` + `distinctUntilChanged`. Re-asserting
   * `true` from the `focusin` that follows in the SAME tick means the debounce emits only the
   * `true`, which is a no-op against the existing state — so the close never fires.
   */
  protected onDocumentFocusIn(event: FocusEvent): void {
    if (!this.isOpen()) {
      return;
    }
    const target = event.target;
    const field = target instanceof Element ? target.closest(`[${MENU_CARET_HANDOFF_ATTR}]`) : null;
    if (!field) {
      return;
    }
    this.menuStack.setHasFocus(true);
    if (this.armedHandoff === field) {
      return;
    }
    this.armedHandoff = field;
    field.addEventListener('input', this.closeOnHandoffInput, { once: true });
  }

  private readonly closeOnHandoffInput = (): void => {
    this.armedHandoff = undefined;
    this.close();
  };

  open(): void {
    this.cdk.open();
  }

  /**
   * Opens the menu with focus on its first or last item, as CDK's trigger does when its element is
   * used: a click (or Enter/Space, which a button turns into one) focuses the first item as `mouse`,
   * the Down and Up arrows the first and last as `keyboard`. For a caller that stood in for the
   * trigger until the user reached for it.
   */
  openWithFocus(item: 'first' | 'last', origin: FocusOrigin): void {
    // The template effect may not have run yet for a trigger created in this same turn.
    this.cdk.menuTemplateRef ??= this.menu().template() ?? null;
    this.cdk.open();
    const menu = this.cdk.getMenu();
    if (item === 'first') {
      menu?.focusFirstItem(origin);
    } else {
      menu?.focusLastItem(origin);
    }
  }

  close(): void {
    this.cdk.close();
  }

  toggle(): void {
    this.cdk.toggle();
  }

  isOpen(): boolean {
    return this.cdk.isOpen();
  }
}
