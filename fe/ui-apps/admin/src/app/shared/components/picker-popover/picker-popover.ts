import { Component, computed, inject, input, signal } from '@angular/core';
import {
  CdkConnectedOverlay,
  type ConnectedPosition,
  Overlay,
  type ScrollStrategy,
} from '@angular/cdk/overlay';

/**
 * Below→above flip solved ONCE at show time. With `lockPosition` (and
 * growAfterOpen / flexibleDimensions off) CDK never re-solves, so the panel
 * opens instantly and never visibly re-positions. When none of the four fits —
 * a short window with the trigger mid-screen — push moves the panel onto the
 * screen, over its trigger if it must; the push is solved at the same moment
 * and frozen with it.
 */
const POSITIONS: ConnectedPosition[] = [
  { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 4 },
  { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 4 },
  { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -4 },
  { originX: 'end', originY: 'top', overlayX: 'end', overlayY: 'bottom', offsetY: -4 },
];

/**
 * The single shell for the app's anchored pickers (tag / area / category /
 * script-resource path).
 *
 * It owns the ONE popover behaviour so the pickers can't drift — and so the
 * positioning/sizing bugs we kept reintroducing have exactly one home:
 * - INSTANT open, no JS re-positioning: the 4-position list is solved once and
 *   `lockPosition` freezes it (CDK, `@angular/cdk/overlay` connected overlay).
 * - Never off-screen: when no position fits, the panel is pushed inside the
 *   viewport rather than opened partly beyond its edge.
 * - Portalled to the body-level `cdk-overlay-container`, so it escapes any
 *   transformed-ancestor stacking-context trap.
 * - A width-bounded flex column for the projected content (`.picker-panel`).
 *
 * The remaining rule lives with each consumer: its scroll region (list / tree)
 * MUST have a fixed, viewport-bounded height (e.g. `h-[min(18rem,45vh)]` or a
 * tree `scrollHeight`). Because the box's size then never depends on async or
 * interactive content, the one-time position is always right — it never
 * overflows and never visibly re-positions. The accepted trade-off: a short
 * list shows some empty space rather than hugging its content.
 *
 * No backdrop (clicks pass through to the page); the
 * trigger's own click owns the re-click-to-close toggle, so `overlayOutsideClick`
 * ignores clicks landing inside the stored anchor.
 */
@Component({
  selector: 'app-picker-popover',
  imports: [CdkConnectedOverlay],
  template: `
    <ng-template
      cdkConnectedOverlay
      [cdkConnectedOverlayOrigin]="anchor()!"
      [cdkConnectedOverlayOpen]="shown() && !!anchor()"
      [cdkConnectedOverlayPositions]="positions"
      [cdkConnectedOverlayScrollStrategy]="scrollStrategy()"
      [cdkConnectedOverlayLockPosition]="true"
      [cdkConnectedOverlayPush]="true"
      [cdkConnectedOverlayGrowAfterOpen]="false"
      [cdkConnectedOverlayFlexibleDimensions]="false"
      [cdkConnectedOverlayViewportMargin]="8"
      [cdkConnectedOverlayHasBackdrop]="false"
      cdkConnectedOverlayPanelClass="picker-overlay"
      (overlayOutsideClick)="onOutsideClick($event)"
      (detach)="close()"
    >
      <!-- picker-panel is a fixed comfortable width for the list pickers (tag/tree/category),
           whose content sets no width of its own. Narrow, fixed-size content (the calendar, the
           time columns) opts into panelWidth=fit-content so the box hugs it instead of leaving
           dead space — the size is still synchronous/stable, so the one-time position stays right. -->
      <div class="picker-panel" [style.width]="panelWidth()">
        <ng-content />
      </div>
    </ng-template>
  `,
  host: {
    class: 'contents',
    '(document:keydown.escape)': 'onEscape($event)',
  },
})
export class PickerPopover {
  private readonly overlay = inject(Overlay);
  /** Override the panel width (e.g. `'fit-content'`) for narrow, fixed-size content; null = the default. */
  readonly panelWidth = input<string | null>(null);
  /**
   * How an open popover reacts to a page scroll. `true` (default) dismisses it — right for the
   * list pickers (tag/tree/category), which shouldn't chase their anchor. `false` freezes the
   * page instead, keeping the popover open — right for the value pickers (date/time), whose own
   * scrollable content makes an accidental scroll-to-dismiss (cursor just outside) feel broken.
   */
  readonly dismissOnScroll = input(true);
  protected readonly positions = POSITIONS;
  private readonly scrollClose: ScrollStrategy = this.overlay.scrollStrategies.close();
  private readonly scrollBlock: ScrollStrategy = this.overlay.scrollStrategies.block();
  protected readonly scrollStrategy = computed<ScrollStrategy>(() =>
    this.dismissOnScroll() ? this.scrollClose : this.scrollBlock,
  );

  protected readonly anchor = signal<HTMLElement | null>(null);
  /** Visibility — read by consumers (tag-picker/single-tag-field/tree-picker) for their re-click toggle. */
  readonly shown = signal(false);

  open(anchor: HTMLElement): void {
    this.anchor.set(anchor);
    this.shown.set(true);
  }

  close(): void {
    this.shown.set(false);
  }

  /** Filter-chip trigger: a re-click while open closes it. */
  toggle(anchor: HTMLElement): void {
    if (this.shown()) {
      this.close();
    } else {
      this.open(anchor);
    }
  }

  protected onOutsideClick(event: MouseEvent): void {
    // Clicks inside the trigger belong to the trigger's own toggle handler —
    // ignore them here (a backdrop would swallow that click and flicker).
    const a = this.anchor();
    if (a && event.target instanceof Node && a.contains(event.target)) return;
    this.close();
  }

  protected onEscape(event: Event): void {
    if (this.shown()) {
      this.close();
      // Stop the drawer's document-level Esc from also firing; the drawer
      // additionally stands down while `.picker-overlay` is in the DOM.
      event.stopPropagation();
    }
  }
}
